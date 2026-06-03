package com.tom.tradeoptimizer.trade;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.network.CycleStatusS2C;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.villager.OfferEntry;
import com.tom.tradeoptimizer.villager.VillagerEntry;
import com.tom.tradeoptimizer.villager.VillagerRegistryState;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side target-aware cycle controller.
 *
 * Each player has at most one active session at a time. The phase machine breaks the
 * workstation, waits cooldown ticks, replaces it, waits for the villager AI to re-roll
 * trades, then reads the new offers. If the target trade signature is present, the
 * session enters FOUND state (player can re-roll or stop). Otherwise the session loops
 * — up to `maxCycleAttempts` from config.
 *
 * Compatible with vanilla mechanics: uses the same `destroyBlock` + `setBlockAndUpdate`
 * the player would. No timing jitter, no packet spoofing.
 */
public final class CycleController {
    private CycleController() {}

    private static final Map<UUID, CycleSession> SESSIONS = new HashMap<>();
    private static boolean tickHooked = false;

    public static final Set<Block> WORKSTATIONS = Set.of(
            Blocks.LECTERN, Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.FLETCHING_TABLE,
            Blocks.GRINDSTONE, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.CARTOGRAPHY_TABLE,
            Blocks.BREWING_STAND, Blocks.COMPOSTER, Blocks.BARREL, Blocks.LOOM, Blocks.CAULDRON
    );

    public static void register() {
        if (tickHooked) return;
        tickHooked = true;
        ServerTickEvents.END_SERVER_TICK.register(CycleController::tick);
    }

    public static void startSession(ServerPlayer player, UUID villagerId, BlockPos workstation, TradeSignature target) {
        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        if (!cfg.cyclingEnabled) {
            sendStatus(player, CycleStatusS2C.State.ENDED, 0, 0, 0,
                    target, "Cycling is disabled in tradeoptimizer.json.");
            return;
        }
        // Note: the 26.1.2 permission API moved from hasPermissions(int) to a
        // PermissionSet/Permission system. We rely on cfg.cyclingEnabled as the on/off
        // switch for now; admins choose whether non-ops can cycle by toggling that flag.

        ServerLevel level = player.level();
        if (!(level.getEntity(villagerId) instanceof Villager villager)) {
            sendStatus(player, CycleStatusS2C.State.ENDED, 0, 0, 0,
                    target, "Villager not found.");
            return;
        }
        if (villager.getVillagerXp() > 0) {
            sendStatus(player, CycleStatusS2C.State.ENDED, 0, 0, 0,
                    target, "Villager has experience — trades are locked.");
            return;
        }

        BlockState state = level.getBlockState(workstation);
        Block block = state.getBlock();
        if (!WORKSTATIONS.contains(block)) {
            sendStatus(player, CycleStatusS2C.State.ENDED, 0, 0, 0,
                    target, "That block isn't a valid villager workstation.");
            return;
        }
        if (player.blockPosition().distSqr(workstation) > 64.0) {
            sendStatus(player, CycleStatusS2C.State.ENDED, 0, 0, 0,
                    target, "Workstation is too far away (max 8 blocks).");
            return;
        }

        // Replace any existing session for this player.
        UUID playerId = player.getUUID();
        SESSIONS.remove(playerId);

        CycleSession session = new CycleSession(playerId, villagerId, workstation, block, level, target);
        // If we already know this villager's best for the target, seed bestCost from history.
        VillagerEntry known = VillagerRegistryState.get(level).get(villagerId);
        if (known != null && session.hasTarget()) {
            Integer hist = known.bestPriceFor(target);
            if (hist != null) session.bestCost = hist;
        }
        session.phase = CycleSession.Phase.BREAKING;
        session.ticksLeft = 1; // start next tick
        SESSIONS.put(playerId, session);

        sendStatus(player, CycleStatusS2C.State.ACTIVE, 0, 0,
                session.bestCost == Integer.MAX_VALUE ? 0 : session.bestCost,
                target,
                session.hasTarget() ? "Hunting for " + target.displayName() : "Re-rolling once...");
        TradeOptimizer.LOGGER.info("Cycle started for {} on villager {}, target {}",
                player.getName().getString(), villagerId, target.displayName());
    }

    public static void stopSession(ServerPlayer player, String reason) {
        CycleSession s = SESSIONS.remove(player.getUUID());
        if (s == null) return;
        sendStatus(player, CycleStatusS2C.State.ENDED, s.attempts, s.lastCost,
                s.bestCost == Integer.MAX_VALUE ? 0 : s.bestCost, s.target, reason);
    }

    public static boolean hasActiveSession(UUID playerId) {
        return SESSIONS.containsKey(playerId);
    }

    private static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;
        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        List<UUID> toRemove = new ArrayList<>();

        for (CycleSession s : new ArrayList<>(SESSIONS.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(s.playerId);
            if (player == null) { toRemove.add(s.playerId); continue; }

            // Range / world checks every tick
            if (player.blockPosition().distSqr(s.workstation) > 64.0) {
                sendStatus(player, CycleStatusS2C.State.ENDED, s.attempts, s.lastCost,
                        s.bestCost == Integer.MAX_VALUE ? 0 : s.bestCost, s.target,
                        "Moved too far from workstation.");
                toRemove.add(s.playerId);
                continue;
            }

            if (s.ticksLeft > 0) { s.ticksLeft--; continue; }

            switch (s.phase) {
                case BREAKING -> {
                    BlockState current = s.level.getBlockState(s.workstation);
                    if (current.getBlock() != s.originalBlock) {
                        sendStatus(player, CycleStatusS2C.State.ENDED, s.attempts, s.lastCost,
                                s.bestCost == Integer.MAX_VALUE ? 0 : s.bestCost, s.target,
                                "Workstation block changed unexpectedly.");
                        toRemove.add(s.playerId);
                        break;
                    }
                    s.level.destroyBlock(s.workstation, false, player);
                    s.phase = CycleSession.Phase.PLACING;
                    s.ticksLeft = cfg.cycleCooldownTicks;
                }
                case PLACING -> {
                    s.level.setBlockAndUpdate(s.workstation, s.originalBlock.defaultBlockState());
                    s.phase = CycleSession.Phase.CHECKING;
                    s.ticksLeft = cfg.postPlaceWaitTicks;
                }
                case CHECKING -> {
                    s.attempts++;
                    int matchedCost = checkForTarget(s);
                    if (matchedCost > 0) {
                        s.lastCost = matchedCost;
                        if (matchedCost < s.bestCost) s.bestCost = matchedCost;
                        s.phase = CycleSession.Phase.FOUND;
                        sendStatus(player, CycleStatusS2C.State.FOUND,
                                s.attempts, s.lastCost, s.bestCost, s.target,
                                "Found " + s.target.displayName() + " at " + matchedCost + " emeralds.");
                        // Stay in FOUND — wait for player action (re-roll or stop)
                    } else if (!s.hasTarget()) {
                        sendStatus(player, CycleStatusS2C.State.ENDED, s.attempts, s.lastCost,
                                s.bestCost == Integer.MAX_VALUE ? 0 : s.bestCost, s.target,
                                "Re-roll complete.");
                        toRemove.add(s.playerId);
                    } else if (s.attempts >= cfg.maxCycleAttempts) {
                        sendStatus(player, CycleStatusS2C.State.ENDED, s.attempts, s.lastCost,
                                s.bestCost == Integer.MAX_VALUE ? 0 : s.bestCost, s.target,
                                "Gave up after " + s.attempts + " attempts.");
                        toRemove.add(s.playerId);
                    } else {
                        // Loop: kick back to BREAKING
                        s.phase = CycleSession.Phase.BREAKING;
                        s.ticksLeft = cfg.cycleCooldownTicks;
                        sendStatus(player, CycleStatusS2C.State.ACTIVE, s.attempts, 0,
                                s.bestCost == Integer.MAX_VALUE ? 0 : s.bestCost, s.target,
                                "Attempt " + s.attempts + "/" + cfg.maxCycleAttempts);
                    }
                }
                case FOUND -> {
                    // Idle in FOUND until player triggers a re-roll (new startSession) or stops.
                }
                default -> {}
            }
        }
        for (UUID id : toRemove) SESSIONS.remove(id);
    }

    /**
     * Read the villager's current offers and return the matched cost, or 0 if no match.
     * Also updates the registry state with the new offers + best-price book-keeping.
     */
    private static int checkForTarget(CycleSession s) {
        if (!(s.level.getEntity(s.villagerId) instanceof Villager villager)) return 0;
        MerchantOffers offers = villager.getOffers();
        if (offers == null || offers.isEmpty()) return 0;

        // Update registry with the new offer set.
        VillagerData data = villager.getVillagerData();
        List<OfferEntry> snap = new ArrayList<>(offers.size());
        int matchedCost = 0;
        for (MerchantOffer offer : offers) {
            OfferEntry entry = OfferEntry.fromMerchantOffer(offer, TradeEvaluator.rate(offer, data.level()));
            snap.add(entry);
            if (s.hasTarget() && entry.signature().equals(s.target) && entry.emeraldCost() > 0) {
                if (matchedCost == 0 || entry.emeraldCost() < matchedCost) matchedCost = entry.emeraldCost();
            }
        }

        VillagerRegistryState state = VillagerRegistryState.get(s.level);
        VillagerEntry existing = state.get(s.villagerId);
        Map<String, Integer> bestPrices = existing != null ? existing.bestPrices() : new HashMap<>();
        VillagerEntry updated = new VillagerEntry(
                s.villagerId,
                net.minecraft.core.registries.BuiltInRegistries.VILLAGER_PROFESSION
                        .getKey(data.profession().value()).toString(),
                data.level(),
                villager.blockPosition(),
                s.level.getGameTime(),
                snap,
                bestPrices
        );
        updated.recordBestPrices();
        state.upsert(updated);
        return matchedCost;
    }

    private static void sendStatus(ServerPlayer player, CycleStatusS2C.State state, int attempts,
                                   int lastCost, int bestCost, TradeSignature target, String msg) {
        if (ServerPlayNetworking.canSend(player, NetworkPayloads.CYCLE_STATUS_TYPE)) {
            ServerPlayNetworking.send(player, CycleStatusS2C.of(state, attempts, lastCost, bestCost, target, msg));
        }
    }
}

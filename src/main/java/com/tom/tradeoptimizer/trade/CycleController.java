package com.tom.tradeoptimizer.trade;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.UUID;

/**
 * Server-side handler for trade-cycle requests.
 *
 * Cycling means: the villager has not yet locked in trades (novice, no experience).
 * Removing and re-placing the workstation block within a tick window forces the
 * villager to re-roll its trade list — the standard vanilla mechanic.
 *
 * Available only on servers that have explicitly installed this mod AND set
 * cyclingEnabled=true in config. No packet spoofing, no timing jitter — just a
 * straightforward server-authoritative break/place sequence on a config cooldown.
 */
public final class CycleController {
    private CycleController() {}

    private record PendingCycle(
            UUID villagerId,
            BlockPos workstation,
            Block originalBlock,
            ServerWorld world,
            ServerPlayerEntity requester,
            int phase,
            int ticksLeft
    ) {}

    private static final Deque<PendingCycle> QUEUE = new ArrayDeque<>();
    private static boolean tickHooked = false;

    private static final Set<Block> WORKSTATIONS = Set.of(
            Blocks.LECTERN,
            Blocks.SMITHING_TABLE,
            Blocks.STONECUTTER,
            Blocks.FLETCHING_TABLE,
            Blocks.GRINDSTONE,
            Blocks.SMOKER,
            Blocks.BLAST_FURNACE,
            Blocks.CARTOGRAPHY_TABLE,
            Blocks.BREWING_STAND,
            Blocks.COMPOSTER,
            Blocks.BARREL,
            Blocks.LOOM,
            Blocks.CAULDRON
    );

    public static void handleRequest(ServerPlayerEntity player, UUID villagerId, BlockPos workstation) {
        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        if (!cfg.cyclingEnabled) {
            player.sendMessage(Text.literal("Trade Optimizer: cycling is disabled in config."), false);
            return;
        }

        // NOTE: server-side runtime op check could be added here once we settle on the 1.21.11
        // permission API. For now, gating via cyclingEnabled is the single source of truth.

        ServerWorld world = player.getEntityWorld();
        if (world == null) return;
        if (!(world.getEntity(villagerId) instanceof VillagerEntity villager)) return;

        if (villager.getExperience() > 0) {
            player.sendMessage(Text.literal(
                    "Trade Optimizer: villager already has experience, cycling won't reset trades."), false);
            return;
        }

        BlockState state = world.getBlockState(workstation);
        Block block = state.getBlock();
        if (!WORKSTATIONS.contains(block)) {
            player.sendMessage(Text.literal("Trade Optimizer: that block isn't a valid villager workstation."), false);
            return;
        }

        if (player.getBlockPos().getSquaredDistance(workstation.toCenterPos()) > 64.0) {
            player.sendMessage(Text.literal("Trade Optimizer: workstation is too far away (max 8 blocks)."), false);
            return;
        }

        QUEUE.add(new PendingCycle(villagerId, workstation, block, world, player, 0, cfg.cycleCooldownTicks));
        TradeOptimizer.LOGGER.info("Queued cycle for villager {} at {}", villagerId, workstation);
    }

    public static void register() {
        if (tickHooked) return;
        tickHooked = true;
        ServerTickEvents.END_SERVER_TICK.register(CycleController::tick);
    }

    private static void tick(MinecraftServer server) {
        if (QUEUE.isEmpty()) return;
        int size = QUEUE.size();
        for (int i = 0; i < size; i++) {
            PendingCycle p = QUEUE.pollFirst();
            if (p == null) break;
            PendingCycle next = step(p);
            if (next != null) QUEUE.add(next);
        }
    }

    private static PendingCycle step(PendingCycle p) {
        if (p.ticksLeft > 0) {
            return new PendingCycle(p.villagerId, p.workstation, p.originalBlock,
                    p.world, p.requester, p.phase, p.ticksLeft - 1);
        }
        int cooldown = TradeOptimizerConfig.get().cycleCooldownTicks;
        switch (p.phase) {
            case 0 -> {
                BlockState current = p.world.getBlockState(p.workstation);
                if (current.getBlock() != p.originalBlock) return null;
                p.world.breakBlock(p.workstation, false, p.requester, 512);
                return new PendingCycle(p.villagerId, p.workstation, p.originalBlock,
                        p.world, p.requester, 1, cooldown);
            }
            case 1 -> {
                return new PendingCycle(p.villagerId, p.workstation, p.originalBlock,
                        p.world, p.requester, 2, 0);
            }
            case 2 -> {
                p.world.setBlockState(p.workstation, p.originalBlock.getDefaultState());
                p.requester.sendMessage(Text.literal("Trade Optimizer: cycle complete."), true);
                return null;
            }
        }
        return null;
    }
}

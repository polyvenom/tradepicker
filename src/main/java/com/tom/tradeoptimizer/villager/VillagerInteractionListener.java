package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.network.TradeSnapshotS2C;
import com.tom.tradeoptimizer.trade.TradeEvaluator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Server side: when a player right-clicks a villager, schedule a 2-tick deferred read
 * of the villager's offers (the merchant menu opens asynchronously, so we wait).
 * Then capture trades, update best-price history, and push a snapshot to that player only.
 */
public final class VillagerInteractionListener {
    private VillagerInteractionListener() {}

    private record PendingCapture(UUID villagerId, UUID playerId, int ticksLeft) {}

    private static final ConcurrentLinkedDeque<PendingCapture> PENDING = new ConcurrentLinkedDeque<>();
    private static final ConcurrentHashMap<UUID, ServerPlayer> CAPTURE_PLAYER = new ConcurrentHashMap<>();

    public static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!(entity instanceof Villager villager)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            schedule(villager, sp);
            return InteractionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int size = PENDING.size();
            for (int i = 0; i < size; i++) {
                PendingCapture p = PENDING.pollFirst();
                if (p == null) break;
                if (p.ticksLeft() > 0) {
                    PENDING.add(new PendingCapture(p.villagerId(), p.playerId(), p.ticksLeft() - 1));
                    continue;
                }
                resolveCapture(p);
            }
        });
    }

    private static void schedule(Villager villager, ServerPlayer requester) {
        UUID vid = villager.getUUID();
        CAPTURE_PLAYER.put(vid, requester);
        PENDING.add(new PendingCapture(vid, requester.getUUID(), 3));
    }

    private static void resolveCapture(PendingCapture p) {
        ServerPlayer requester = CAPTURE_PLAYER.remove(p.villagerId());
        if (requester == null) return;
        ServerLevel level = requester.level();
        if (level == null) return;
        if (!(level.getEntity(p.villagerId()) instanceof Villager villager)) return;

        VillagerData data = villager.getVillagerData();
        MerchantOffers offers = villager.getOffers();
        if (offers == null || offers.isEmpty()) {
            // Send empty snapshot so client knows to clear overlay
            ServerPlayNetworking.send(requester, TradeSnapshotS2C.empty());
            return;
        }

        List<OfferEntry> snap = new ArrayList<>(offers.size());
        for (MerchantOffer offer : offers) {
            snap.add(OfferEntry.fromMerchantOffer(offer, TradeEvaluator.rate(offer, data.level())));
        }

        VillagerRegistryState state = VillagerRegistryState.get(level);
        VillagerEntry existing = state.get(p.villagerId());
        var bestPrices = existing != null ? existing.bestPrices() : new HashMap<String, Integer>();

        VillagerEntry entry = new VillagerEntry(
                p.villagerId(),
                BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.profession().value()).toString(),
                data.level(),
                villager.blockPosition(),
                level.getGameTime(),
                snap,
                bestPrices
        );
        entry.recordBestPrices();
        state.upsert(entry);

        if (ServerPlayNetworking.canSend(requester, NetworkPayloads.SNAPSHOT_TYPE)) {
            ServerPlayNetworking.send(requester, TradeSnapshotS2C.of(entry));
        }

        TradeOptimizer.LOGGER.debug("Snapshot sent: {} trades from villager {} to {}",
                snap.size(), p.villagerId(), requester.getName().getString());
    }
}

package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.network.VillagerSyncS2C;
import com.tom.tradeoptimizer.trade.TradeEvaluator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.village.VillagerData;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class VillagerInteractionListener {
    private VillagerInteractionListener() {}

    private record PendingCapture(UUID id, int ticksLeft) {}

    private static final ConcurrentLinkedDeque<PendingCapture> PENDING = new ConcurrentLinkedDeque<>();
    private static final ConcurrentHashMap<UUID, ServerPlayerEntity> CAPTURE_REQUESTER = new ConcurrentHashMap<>();

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (!(entity instanceof VillagerEntity villager)) return ActionResult.PASS;
            if (!(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            schedule(villager, sp);
            return ActionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int size = PENDING.size();
            for (int i = 0; i < size; i++) {
                PendingCapture p = PENDING.pollFirst();
                if (p == null) break;
                if (p.ticksLeft() > 0) {
                    PENDING.add(new PendingCapture(p.id(), p.ticksLeft() - 1));
                    continue;
                }
                resolveCapture(server, p);
            }
        });
    }

    private static void schedule(VillagerEntity villager, ServerPlayerEntity requester) {
        UUID id = villager.getUuid();
        CAPTURE_REQUESTER.put(id, requester);
        PENDING.add(new PendingCapture(id, 2));
    }

    private static void resolveCapture(MinecraftServer server, PendingCapture p) {
        ServerPlayerEntity requester = CAPTURE_REQUESTER.remove(p.id());
        if (requester == null) return;
        ServerWorld world = requester.getEntityWorld();
        if (world == null) return;
        if (!(world.getEntity(p.id()) instanceof VillagerEntity villager)) return;

        VillagerData data = villager.getVillagerData();
        TradeOfferList offers = villager.getOffers();
        if (offers == null) return;

        List<OfferEntry> snap = new ArrayList<>(offers.size());
        for (TradeOffer offer : offers) {
            snap.add(OfferEntry.fromTradeOffer(offer, TradeEvaluator.rate(offer, data.level())));
        }

        VillagerEntry entry = new VillagerEntry(
                villager.getUuid(),
                data.profession().getIdAsString(),
                data.level(),
                villager.getBlockPos(),
                world.getTime(),
                snap
        );

        VillagerRegistryState state = VillagerRegistryState.get(world);
        state.upsert(entry);

        VillagerSyncS2C payload = VillagerSyncS2C.of(state.all());
        for (ServerPlayerEntity sp : world.getServer().getPlayerManager().getPlayerList()) {
            if (ServerPlayNetworking.canSend(sp, NetworkPayloads.SYNC_ID)) {
                ServerPlayNetworking.send(sp, payload);
            }
        }

        TradeOptimizer.LOGGER.debug("Captured {} trades from villager {}", snap.size(), p.id());
    }
}

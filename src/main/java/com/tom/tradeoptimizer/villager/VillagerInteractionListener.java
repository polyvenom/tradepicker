package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.network.VillagerSyncS2C;
import com.tom.tradeoptimizer.trade.TradeEvaluator;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class VillagerInteractionListener {
    private VillagerInteractionListener() {}

    private record PendingCapture(UUID id, int ticksLeft) {}

    private static final ConcurrentLinkedDeque<PendingCapture> PENDING = new ConcurrentLinkedDeque<>();
    private static final ConcurrentHashMap<UUID, ServerPlayer> CAPTURE_REQUESTER = new ConcurrentHashMap<>();

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
                    PENDING.add(new PendingCapture(p.id(), p.ticksLeft() - 1));
                    continue;
                }
                resolveCapture(server, p);
            }
        });
    }

    private static void schedule(Villager villager, ServerPlayer requester) {
        UUID id = villager.getUUID();
        CAPTURE_REQUESTER.put(id, requester);
        PENDING.add(new PendingCapture(id, 2));
    }

    private static void resolveCapture(MinecraftServer server, PendingCapture p) {
        ServerPlayer requester = CAPTURE_REQUESTER.remove(p.id());
        if (requester == null) return;
        ServerLevel level = (ServerLevel) requester.level();
        if (level == null) return;
        if (!(level.getEntity(p.id()) instanceof Villager villager)) return;

        VillagerData data = villager.getVillagerData();
        MerchantOffers offers = villager.getOffers();
        if (offers == null) return;

        List<OfferEntry> snap = new ArrayList<>(offers.size());
        for (MerchantOffer offer : offers) {
            snap.add(OfferEntry.fromTradeOffer(offer, TradeEvaluator.rate(offer, data.level())));
        }

        VillagerEntry entry = new VillagerEntry(
                villager.getUUID(),
                BuiltInRegistries.VILLAGER_PROFESSION.getKey(data.profession().value()).toString(),
                data.level(),
                villager.blockPosition(),
                level.getGameTime(),
                snap
        );

        VillagerRegistryState state = VillagerRegistryState.get(level);
        state.upsert(entry);

        VillagerSyncS2C payload = VillagerSyncS2C.of(state.all());
        for (ServerPlayer sp : level.getServer().getPlayerList().getPlayers()) {
            if (ServerPlayNetworking.canSend(sp, NetworkPayloads.SYNC_ID)) {
                ServerPlayNetworking.send(sp, payload);
            }
        }

        TradeOptimizer.LOGGER.debug("Captured {} trades from villager {}", snap.size(), p.id());
    }
}
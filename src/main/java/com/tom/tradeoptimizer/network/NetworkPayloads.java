package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.TradeOptimizer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public final class NetworkPayloads {
    private NetworkPayloads() {}

    public static final CustomPayload.Id<VillagerSyncS2C> SYNC_ID =
            new CustomPayload.Id<>(Identifier.of(TradeOptimizer.MOD_ID, "villager_sync"));

    public static final CustomPayload.Id<CycleRequestC2S> CYCLE_ID =
            new CustomPayload.Id<>(Identifier.of(TradeOptimizer.MOD_ID, "cycle_request"));

    public static void registerCommon() {
        PayloadTypeRegistry.playS2C().register(SYNC_ID, VillagerSyncS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(CYCLE_ID, CycleRequestC2S.CODEC);
    }

    public static void registerClient() {
        // Client-only payload registration; currently nothing extra beyond common
        // because handlers attach themselves directly in ClientNetworkHandler.
    }
}

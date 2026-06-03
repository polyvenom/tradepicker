package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.TradeOptimizer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class NetworkPayloads {
    private NetworkPayloads() {}

    public static final CustomPacketPayload.Type<VillagerSyncS2C> SYNC_ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, "villager_sync"));

    public static final CustomPacketPayload.Type<CycleRequestC2S> CYCLE_ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, "cycle_request"));

    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(SYNC_ID, VillagerSyncS2C.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(CYCLE_ID, CycleRequestC2S.CODEC);
    }

    public static void registerClient() {
    }
}
package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.TradeOptimizer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class NetworkPayloads {
    private NetworkPayloads() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, path);
    }

    public static final CustomPacketPayload.Type<TradeSnapshotS2C> SNAPSHOT_TYPE =
            new CustomPacketPayload.Type<>(id("trade_snapshot"));

    public static final CustomPacketPayload.Type<StartCycleC2S> START_CYCLE_TYPE =
            new CustomPacketPayload.Type<>(id("start_cycle"));

    public static final CustomPacketPayload.Type<StopCycleC2S> STOP_CYCLE_TYPE =
            new CustomPacketPayload.Type<>(id("stop_cycle"));

    public static final CustomPacketPayload.Type<CycleStatusS2C> CYCLE_STATUS_TYPE =
            new CustomPacketPayload.Type<>(id("cycle_status"));

    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(SNAPSHOT_TYPE, TradeSnapshotS2C.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(CYCLE_STATUS_TYPE, CycleStatusS2C.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(START_CYCLE_TYPE, StartCycleC2S.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(STOP_CYCLE_TYPE, StopCycleC2S.STREAM_CODEC);
    }
}

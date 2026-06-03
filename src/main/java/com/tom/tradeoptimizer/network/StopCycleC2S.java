package com.tom.tradeoptimizer.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: cancel the active cycle session for this player. */
public record StopCycleC2S() implements CustomPacketPayload {

    public static final StopCycleC2S INSTANCE = new StopCycleC2S();

    public static final StreamCodec<RegistryFriendlyByteBuf, StopCycleC2S> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return NetworkPayloads.STOP_CYCLE_TYPE;
    }
}

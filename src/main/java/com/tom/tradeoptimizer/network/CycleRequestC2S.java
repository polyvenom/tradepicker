package com.tom.tradeoptimizer.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

public record CycleRequestC2S(UUID villagerId, BlockPos workstation) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, CycleRequestC2S> CODEC = StreamCodec.of(
            (buf, value) -> {
                buf.writeUUID(value.villagerId);
                buf.writeBlockPos(value.workstation);
            },
            buf -> new CycleRequestC2S(buf.readUUID(), buf.readBlockPos())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return NetworkPayloads.CYCLE_ID;
    }
}
package com.tom.tradeoptimizer.network;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.math.BlockPos;

import java.util.UUID;

public record CycleRequestC2S(UUID villagerId, BlockPos workstation) implements CustomPayload {

    public static final PacketCodec<RegistryByteBuf, CycleRequestC2S> CODEC = PacketCodec.of(
            (value, buf) -> {
                buf.writeUuid(value.villagerId);
                buf.writeBlockPos(value.workstation);
            },
            buf -> new CycleRequestC2S(buf.readUuid(), buf.readBlockPos())
    );

    @Override
    public Id<? extends CustomPayload> getId() {
        return NetworkPayloads.CYCLE_ID;
    }
}

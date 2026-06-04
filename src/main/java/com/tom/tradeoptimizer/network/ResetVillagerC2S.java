package com.tom.tradeoptimizer.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Client -> server: please reset this villager — clear all picks, drop XP/level back
 * to Novice, and re-open the picker. Client UI confirms before sending so this can't
 * fire by accident.
 */
public record ResetVillagerC2S(UUID villagerId) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, ResetVillagerC2S> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeUUID(p.villagerId),
            buf -> new ResetVillagerC2S(buf.readUUID())
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return NetworkPayloads.RESET_VILLAGER_TYPE;
    }
}

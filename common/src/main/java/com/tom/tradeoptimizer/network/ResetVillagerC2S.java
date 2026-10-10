package com.tom.tradeoptimizer.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Client -> server: reset this villager (clear picks, drop XP/level to Novice, wipe offers).
 * The server replies with a chat message; it does not auto-open the picker.
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

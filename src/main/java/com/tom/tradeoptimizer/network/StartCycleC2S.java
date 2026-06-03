package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.trade.TradeSignature;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Client -> server: please start cycling this villager's workstation, hunting for `target`.
 * If `target.sellItemId()` is empty, do a single re-roll (no automatic continuation).
 */
public record StartCycleC2S(UUID villagerId, BlockPos workstation, TradeSignature target) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, StartCycleC2S> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUUID(p.villagerId);
                buf.writeBlockPos(p.workstation);
                TradeSignature.STREAM_CODEC.encode(buf, p.target);
            },
            buf -> new StartCycleC2S(
                    buf.readUUID(),
                    buf.readBlockPos(),
                    TradeSignature.STREAM_CODEC.decode(buf))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return NetworkPayloads.START_CYCLE_TYPE;
    }
}

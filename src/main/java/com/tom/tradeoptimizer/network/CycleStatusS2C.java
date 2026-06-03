package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.trade.TradeSignature;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client: live status of the cycle session.
 *
 *  - state IDLE   = no cycle in progress
 *  - state ACTIVE = cycling, search continues
 *  - state FOUND  = target rolled successfully; player can re-roll for cheaper
 *  - state ENDED  = stopped by user, range, or attempt cap
 */
public record CycleStatusS2C(int stateOrdinal, int attempts, int lastCost, int bestCost, TradeSignature target, String message)
        implements CustomPacketPayload {

    public enum State { IDLE, ACTIVE, FOUND, ENDED }

    public State state() {
        return stateOrdinal >= 0 && stateOrdinal < State.values().length
                ? State.values()[stateOrdinal] : State.IDLE;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, CycleStatusS2C> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeByte(p.stateOrdinal);
                buf.writeVarInt(p.attempts);
                buf.writeVarInt(p.lastCost);
                buf.writeVarInt(p.bestCost);
                TradeSignature.STREAM_CODEC.encode(buf, p.target);
                buf.writeUtf(p.message);
            },
            buf -> new CycleStatusS2C(
                    buf.readByte() & 0xFF,
                    buf.readVarInt(),
                    buf.readVarInt(),
                    buf.readVarInt(),
                    TradeSignature.STREAM_CODEC.decode(buf),
                    buf.readUtf())
    );

    public static CycleStatusS2C of(State s, int attempts, int last, int best, TradeSignature target, String msg) {
        return new CycleStatusS2C(s.ordinal(), attempts, last, best, target, msg);
    }

    public static CycleStatusS2C idle() {
        return of(State.IDLE, 0, 0, 0, TradeSignature.EMPTY, "");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return NetworkPayloads.CYCLE_STATUS_TYPE;
    }
}

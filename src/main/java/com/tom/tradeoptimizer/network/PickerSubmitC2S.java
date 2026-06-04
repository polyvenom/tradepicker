package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.trade.TradeKey;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client -> server: the player picked these trades for the indicated villager+level.
 * Server validates picks against the actual TradeSet contents before applying.
 */
public record PickerSubmitC2S(UUID villagerId, int level, List<TradeKey> picks) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, PickerSubmitC2S> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUUID(p.villagerId);
                buf.writeVarInt(p.level);
                buf.writeVarInt(p.picks.size());
                for (TradeKey k : p.picks) TradeKey.STREAM_CODEC.encode(buf, k);
            },
            buf -> {
                UUID id = buf.readUUID();
                int level = buf.readVarInt();
                int n = buf.readVarInt();
                List<TradeKey> picks = new ArrayList<>(n);
                for (int i = 0; i < n; i++) picks.add(TradeKey.STREAM_CODEC.decode(buf));
                return new PickerSubmitC2S(id, level, picks);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return NetworkPayloads.PICKER_SUBMIT_TYPE;
    }
}

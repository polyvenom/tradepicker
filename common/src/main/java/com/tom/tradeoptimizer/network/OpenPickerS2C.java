package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.TradeKey;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server -> client: open the trade picker for this villager at this level.
 *
 *  villagerId       — the entity UUID to remember on the client side
 *  profession       — display string ("FARMER", "LIBRARIAN", etc.)
 *  level            — the merchant level being picked (1-5)
 *  picksRequired    — how many trades the player must select (at most 2)
 *  maxBookPicks     — how many of those picks may be enchanted books; picksRequired when
 *                     vanillaBookLimits is off
 *  available        — every possible trade for (profession, level) with min-cost preview
 *  ownedKeys        — keys within {@code available} the villager already sells (#7), which the
 *                     client marks. Empty when the server filtered them out (hidePickedTrades).
 */
public record OpenPickerS2C(
        UUID villagerId,
        String profession,
        int level,
        int picksRequired,
        int maxBookPicks,
        List<AvailableTrade> available,
        List<TradeKey> ownedKeys
) implements CustomPacketPayload {

    /**
     * Upper bound on trade previews read off the wire: a librarian's full book expansion is
     * ~100-200 entries, and a cap stops a malicious server forcing a huge pre-allocation.
     */
    private static final int MAX_TRADES = 4096;

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenPickerS2C> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUUID(p.villagerId);
                buf.writeUtf(p.profession);
                buf.writeVarInt(p.level);
                buf.writeVarInt(p.picksRequired);
                buf.writeVarInt(p.maxBookPicks);
                buf.writeVarInt(p.available.size());
                for (AvailableTrade t : p.available) AvailableTrade.STREAM_CODEC.encode(buf, t);
                buf.writeVarInt(p.ownedKeys.size());
                for (TradeKey k : p.ownedKeys) TradeKey.STREAM_CODEC.encode(buf, k);
            },
            buf -> {
                UUID id = buf.readUUID();
                String prof = buf.readUtf();
                int level = buf.readVarInt();
                int required = buf.readVarInt();
                int maxBookPicks = buf.readVarInt();
                int n = buf.readVarInt();
                if (n < 0 || n > MAX_TRADES) {
                    throw new DecoderException("OpenPickerS2C trade count out of range: " + n);
                }
                List<AvailableTrade> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(AvailableTrade.STREAM_CODEC.decode(buf));
                int owned = buf.readVarInt();
                if (owned < 0 || owned > MAX_TRADES) {
                    throw new DecoderException("OpenPickerS2C owned-key count out of range: " + owned);
                }
                List<TradeKey> ownedKeys = new ArrayList<>(owned);
                for (int i = 0; i < owned; i++) ownedKeys.add(TradeKey.STREAM_CODEC.decode(buf));
                return new OpenPickerS2C(id, prof, level, required, maxBookPicks, list, ownedKeys);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return NetworkPayloads.OPEN_PICKER_TYPE;
    }
}

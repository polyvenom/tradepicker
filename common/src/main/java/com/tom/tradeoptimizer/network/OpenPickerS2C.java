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
 *  picksRequired    — how many trades the player must select (vanilla = 2)
 *  maxBookPicks     — how many of those picks may be enchanted books. Equals picksRequired when
 *                     vanillaBookLimits is off (no effective limit); when on it's vanilla's
 *                     per-level book-trade count (usually 1), so the rest must be non-book trades.
 *  available        — every possible trade for (profession, level) with min-cost preview
 *  ownedKeys        — keys within {@code available} that this villager ALREADY sells (picked at
 *                     an earlier level, or matching a legacy/imported offer). The client marks
 *                     those cards so the player doesn't re-pick a trade they have (issue #7).
 *                     Empty when the server already filtered them out (hidePickedTrades on).
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
     * Upper bound on trade previews read off the wire. A librarian's full enchanted-book
     * expansion is ~100-200 entries; 4096 is comfortably above any real pool while
     * stopping a crafted/malicious server from forcing a huge ArrayList pre-allocation
     * on the client.
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

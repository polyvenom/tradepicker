package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.trade.AvailableTrade;
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
 *  available        — every possible trade for (profession, level) with min-cost preview
 */
public record OpenPickerS2C(
        UUID villagerId,
        String profession,
        int level,
        int picksRequired,
        List<AvailableTrade> available
) implements CustomPacketPayload {

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenPickerS2C> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUUID(p.villagerId);
                buf.writeUtf(p.profession);
                buf.writeVarInt(p.level);
                buf.writeVarInt(p.picksRequired);
                buf.writeVarInt(p.available.size());
                for (AvailableTrade t : p.available) AvailableTrade.STREAM_CODEC.encode(buf, t);
            },
            buf -> {
                UUID id = buf.readUUID();
                String prof = buf.readUtf();
                int level = buf.readVarInt();
                int required = buf.readVarInt();
                int n = buf.readVarInt();
                List<AvailableTrade> list = new ArrayList<>(n);
                for (int i = 0; i < n; i++) list.add(AvailableTrade.STREAM_CODEC.decode(buf));
                return new OpenPickerS2C(id, prof, level, required, list);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return NetworkPayloads.OPEN_PICKER_TYPE;
    }
}

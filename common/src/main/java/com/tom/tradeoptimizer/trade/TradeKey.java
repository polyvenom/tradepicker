package com.tom.tradeoptimizer.trade;

import com.mojang.serialization.Codec;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.trading.VillagerTrade;

/**
 * Identifies one VillagerTrade by registry id, or a synthetic expanded card (book, gear, arrow,
 * stew, dye; see OfferFactory). This is the player's choice payload in the picker.
 */
public record TradeKey(Identifier id) {

    public static final Codec<TradeKey> CODEC = Identifier.CODEC.xmap(TradeKey::new, TradeKey::id);

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeKey> STREAM_CODEC = StreamCodec.of(
            (buf, key) -> buf.writeIdentifier(key.id),
            buf -> new TradeKey(buf.readIdentifier())
    );

    public ResourceKey<VillagerTrade> asResourceKey() {
        return ResourceKey.create(Registries.VILLAGER_TRADE, id);
    }
}

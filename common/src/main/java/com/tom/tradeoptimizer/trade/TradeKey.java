package com.tom.tradeoptimizer.trade;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Identifies one selectable trade in the picker. Trades are hardcoded ItemListing arrays with
 * no registry ids, so every key is synthetic:
 *
 *   tradeoptimizer:listing/&lt;merchantLevel&gt;/&lt;index&gt;   — a flat trade: the index-th
 *       ItemListing of the profession's pool at that level.
 *   tradeoptimizer:book/&lt;enchLevel&gt;/&lt;ns&gt;/&lt;path&gt;      — an expanded enchanted-book
 *       card (one per enchantment × level).
 */
public record TradeKey(ResourceLocation id) {

    public static final Codec<TradeKey> CODEC = ResourceLocation.CODEC.xmap(TradeKey::new, TradeKey::id);

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeKey> STREAM_CODEC = StreamCodec.of(
            (buf, key) -> buf.writeResourceLocation(key.id),
            buf -> new TradeKey(buf.readResourceLocation())
    );
}

package com.tom.tradeoptimizer.trade;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Identifies one selectable trade in the picker. MC 1.21.1 has no villager-trade registry
 * (trades are hardcoded ItemListing arrays), so every key is synthetic:
 *
 *   tradeoptimizer:listing/&lt;merchantLevel&gt;/&lt;index&gt;   — a flat trade: the index-th
 *       ItemListing of the profession's pool at that level.
 *   tradeoptimizer:book/&lt;enchLevel&gt;/&lt;ns&gt;/&lt;path&gt;      — an expanded enchanted-book
 *       card (one per enchantment × level), same format as the 26.x line.
 */
public record TradeKey(ResourceLocation id) {

    public static final Codec<TradeKey> CODEC = ResourceLocation.CODEC.xmap(TradeKey::new, TradeKey::id);

    public static final StreamCodec<RegistryFriendlyByteBuf, TradeKey> STREAM_CODEC = StreamCodec.of(
            (buf, key) -> buf.writeResourceLocation(key.id),
            buf -> new TradeKey(buf.readResourceLocation())
    );
}

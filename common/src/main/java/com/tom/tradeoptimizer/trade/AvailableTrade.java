package com.tom.tradeoptimizer.trade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * One trade option in the picker grid: its key plus a min-cost preview MerchantOffer, so the
 * client can display it without the trade registry.
 */
public record AvailableTrade(TradeKey key, MerchantOffer previewOffer) {

    public static final StreamCodec<RegistryFriendlyByteBuf, AvailableTrade> STREAM_CODEC = StreamCodec.of(
            (buf, t) -> {
                TradeKey.STREAM_CODEC.encode(buf, t.key);
                MerchantOffer.STREAM_CODEC.encode(buf, t.previewOffer);
            },
            buf -> new AvailableTrade(
                    TradeKey.STREAM_CODEC.decode(buf),
                    MerchantOffer.STREAM_CODEC.decode(buf))
    );
}

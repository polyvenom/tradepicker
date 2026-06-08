package com.tom.tradeoptimizer.trade;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * One trade option as shown in the picker grid: its identifying key + a preview
 * MerchantOffer at min cost so the client can display "what you'd give -> what
 * you'd get" without needing the registry on the client side.
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

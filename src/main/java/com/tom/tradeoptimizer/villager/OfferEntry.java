package com.tom.tradeoptimizer.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tom.tradeoptimizer.trade.TradeRating;
import net.minecraft.item.ItemStack;
import net.minecraft.village.TradeOffer;

public record OfferEntry(
        ItemStack firstBuy,
        ItemStack secondBuy,
        ItemStack sell,
        int uses,
        int maxUses,
        boolean disabled,
        TradeRating rating
) {
    public static final Codec<OfferEntry> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            ItemStack.OPTIONAL_CODEC.fieldOf("first").forGetter(OfferEntry::firstBuy),
            ItemStack.OPTIONAL_CODEC.fieldOf("second").forGetter(OfferEntry::secondBuy),
            ItemStack.OPTIONAL_CODEC.fieldOf("sell").forGetter(OfferEntry::sell),
            Codec.INT.fieldOf("uses").forGetter(OfferEntry::uses),
            Codec.INT.fieldOf("max").forGetter(OfferEntry::maxUses),
            Codec.BOOL.fieldOf("disabled").forGetter(OfferEntry::disabled),
            Codec.STRING.xmap(OfferEntry::ratingFromName, Enum::name).fieldOf("rating").forGetter(OfferEntry::rating)
    ).apply(inst, OfferEntry::new));

    private static TradeRating ratingFromName(String s) {
        try {
            return TradeRating.valueOf(s);
        } catch (IllegalArgumentException e) {
            return TradeRating.UNKNOWN;
        }
    }

    public static OfferEntry fromTradeOffer(TradeOffer offer, TradeRating rating) {
        return new OfferEntry(
                offer.getOriginalFirstBuyItem().copy(),
                offer.getDisplayedSecondBuyItem().copy(),
                offer.getSellItem().copy(),
                offer.getUses(),
                offer.getMaxUses(),
                offer.isDisabled(),
                rating
        );
    }
}

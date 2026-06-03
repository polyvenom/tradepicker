package com.tom.tradeoptimizer.trade;

import net.minecraft.core.Holder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;

public final class TradeEvaluator {
    private TradeEvaluator() {}

    public static TradeRating rate(MerchantOffer offer, int villagerLevel) {
        ItemStack first = offer.getBaseCostA();
        ItemStack sell = offer.getResult();

        if (sell.is(Items.ENCHANTED_BOOK)) {
            return rateEnchantedBook(sell);
        }

        if (sell.is(Items.EMERALD)) {
            BaselinePrices.Range range = BaselinePrices.buyRange(first.getItem());
            if (range == null) return TradeRating.UNKNOWN;
            return classify(first.getCount(), range, true);
        }

        if (first.is(Items.EMERALD)) {
            BaselinePrices.Range range = BaselinePrices.sellRange(sell.getItem());
            if (range == null) return TradeRating.UNKNOWN;
            return classify(sell.getCount(), range, false);
        }

        return TradeRating.UNKNOWN;
    }

    private static TradeRating classify(int amount, BaselinePrices.Range range, boolean lowerIsBetter) {
        if (lowerIsBetter) {
            if (amount <= range.min()) return TradeRating.GREAT;
            if (amount <= range.mid()) return TradeRating.GOOD;
            if (amount <  range.max()) return TradeRating.FAIR;
            return TradeRating.BAD;
        } else {
            if (amount >= range.max()) return TradeRating.GREAT;
            if (amount >= range.mid()) return TradeRating.GOOD;
            if (amount >  range.min()) return TradeRating.FAIR;
            return TradeRating.BAD;
        }
    }

    private static TradeRating rateEnchantedBook(ItemStack book) {
        ItemEnchantments enchants = EnchantmentHelper.getEnchantmentsForCrafting(book);
        boolean hasTopTier = false;
        boolean hasUseful = false;
        for (Holder<Enchantment> ench : enchants.keySet()) {
            int level = enchants.getLevel(ench);
            if (ench.is(Enchantments.MENDING)) hasTopTier = true;
            else if (ench.is(Enchantments.SILK_TOUCH)) hasTopTier = true;
            else if (ench.is(Enchantments.FORTUNE) && level >= 3) hasTopTier = true;
            else if (ench.is(Enchantments.UNBREAKING) && level >= 3) hasUseful = true;
            else if (ench.is(Enchantments.EFFICIENCY) && level >= 4) hasUseful = true;
            else hasUseful = true;
        }
        if (hasTopTier) return TradeRating.GREAT;
        if (hasUseful) return TradeRating.GOOD;
        return TradeRating.FAIR;
    }
}
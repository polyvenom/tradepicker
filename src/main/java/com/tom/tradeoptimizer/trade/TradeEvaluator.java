package com.tom.tradeoptimizer.trade;

import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.village.TradeOffer;

public final class TradeEvaluator {
    private TradeEvaluator() {}

    public static TradeRating rate(TradeOffer offer, int villagerLevel) {
        ItemStack first = offer.getOriginalFirstBuyItem();
        ItemStack sell = offer.getSellItem();

        if (sell.isOf(Items.ENCHANTED_BOOK)) {
            return rateEnchantedBook(sell);
        }

        if (sell.isOf(Items.EMERALD)) {
            BaselinePrices.Range range = BaselinePrices.buyRange(first.getItem());
            if (range == null) return TradeRating.UNKNOWN;
            return classify(first.getCount(), range, true);
        }

        if (first.isOf(Items.EMERALD)) {
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
        ItemEnchantmentsComponent enchants = EnchantmentHelper.getEnchantments(book);
        boolean hasTopTier = false;
        boolean hasUseful = false;
        for (RegistryEntry<Enchantment> ench : enchants.getEnchantments()) {
            int level = enchants.getLevel(ench);
            if (ench.matchesKey(Enchantments.MENDING)) hasTopTier = true;
            else if (ench.matchesKey(Enchantments.SILK_TOUCH)) hasTopTier = true;
            else if (ench.matchesKey(Enchantments.FORTUNE) && level >= 3) hasTopTier = true;
            else if (ench.matchesKey(Enchantments.UNBREAKING) && level >= 3) hasUseful = true;
            else if (ench.matchesKey(Enchantments.EFFICIENCY) && level >= 4) hasUseful = true;
            else hasUseful = true;
        }
        if (hasTopTier) return TradeRating.GREAT;
        if (hasUseful) return TradeRating.GOOD;
        return TradeRating.FAIR;
    }
}

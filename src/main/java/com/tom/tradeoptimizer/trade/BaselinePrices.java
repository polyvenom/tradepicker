package com.tom.tradeoptimizer.trade;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Baseline emerald-equivalent values for known trades.
 *
 * Format per item: { minBuyAmount, maxBuyAmount } for "X of this item = 1 emerald".
 * For sell-side trades (emerald -> item), format is { minSellAmount, maxSellAmount }
 * for "1 emerald = X of this item", stored in the sells map.
 */
public final class BaselinePrices {
    private BaselinePrices() {}

    public record Range(int min, int max) {
        public int mid() { return (min + max) / 2; }
    }

    private static final Map<Identifier, Range> BUYS = new HashMap<>();
    private static final Map<Identifier, Range> SELLS = new HashMap<>();

    private static void buy(Item item, int min, int max) {
        BUYS.put(Registries.ITEM.getId(item), new Range(min, max));
    }

    private static void sell(Item item, int min, int max) {
        SELLS.put(Registries.ITEM.getId(item), new Range(min, max));
    }

    static {
        // Librarian buys
        buy(Items.PAPER, 24, 36);
        buy(Items.BOOK, 4, 6);
        buy(Items.INK_SAC, 5, 7);
        buy(Items.WRITABLE_BOOK, 2, 3);
        // Farmer buys
        buy(Items.WHEAT, 18, 22);
        buy(Items.POTATO, 22, 28);
        buy(Items.CARROT, 18, 22);
        buy(Items.PUMPKIN, 6, 10);
        buy(Items.SUGAR_CANE, 4, 6);
        // Fletcher buys
        buy(Items.STICK, 28, 36);
        buy(Items.STRING, 12, 18);
        buy(Items.FEATHER, 22, 26);
        // Cleric buys
        buy(Items.ROTTEN_FLESH, 28, 36);
        buy(Items.GOLD_INGOT, 2, 4);
        buy(Items.RABBIT_FOOT, 1, 1);
        // Mason buys
        buy(Items.CLAY_BALL, 8, 12);
        buy(Items.STONE, 18, 22);
        // Cartographer buys
        buy(Items.COMPASS, 1, 1);
        buy(Items.GLASS_PANE, 9, 13);
        // Common sells (emerald -> X items per emerald)
        sell(Items.GLASS, 3, 5);
        sell(Items.LANTERN, 1, 1);
        sell(Items.REDSTONE, 1, 2);
        sell(Items.LAPIS_LAZULI, 1, 1);
    }

    public static Range buyRange(Item item) {
        return BUYS.get(Registries.ITEM.getId(item));
    }

    public static Range sellRange(Item item) {
        return SELLS.get(Registries.ITEM.getId(item));
    }
}

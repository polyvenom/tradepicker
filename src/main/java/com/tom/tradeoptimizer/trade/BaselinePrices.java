package com.tom.tradeoptimizer.trade;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

public final class BaselinePrices {
    private BaselinePrices() {}

    public record Range(int min, int max) {
        public int mid() { return (min + max) / 2; }
    }

    private static final Map<String, Range> BUYS = new HashMap<>();
    private static final Map<String, Range> SELLS = new HashMap<>();

    private static void buy(Item item, int min, int max) {
        BUYS.put(BuiltInRegistries.ITEM.getKey(item).toString(), new Range(min, max));
    }

    private static void sell(Item item, int min, int max) {
        SELLS.put(BuiltInRegistries.ITEM.getKey(item).toString(), new Range(min, max));
    }

    static {
        buy(Items.PAPER, 24, 36);
        buy(Items.BOOK, 4, 6);
        buy(Items.INK_SAC, 5, 7);
        buy(Items.WRITABLE_BOOK, 2, 3);
        buy(Items.WHEAT, 18, 22);
        buy(Items.POTATO, 22, 28);
        buy(Items.CARROT, 18, 22);
        buy(Items.PUMPKIN, 6, 10);
        buy(Items.SUGAR_CANE, 4, 6);
        buy(Items.STICK, 28, 36);
        buy(Items.STRING, 12, 18);
        buy(Items.FEATHER, 22, 26);
        buy(Items.ROTTEN_FLESH, 28, 36);
        buy(Items.GOLD_INGOT, 2, 4);
        buy(Items.RABBIT_FOOT, 1, 1);
        buy(Items.CLAY_BALL, 8, 12);
        buy(Items.STONE, 18, 22);
        buy(Items.COMPASS, 1, 1);
        buy(Items.GLASS_PANE, 9, 13);
        sell(Items.GLASS, 3, 5);
        sell(Items.LANTERN, 1, 1);
        sell(Items.REDSTONE, 1, 2);
        sell(Items.LAPIS_LAZULI, 1, 1);
    }

    public static Range buyRange(Item item) {
        return BUYS.get(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    public static Range sellRange(Item item) {
        return SELLS.get(BuiltInRegistries.ITEM.getKey(item).toString());
    }
}
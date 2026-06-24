package com.tom.tradeoptimizer.trade;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public class BookKeyFormatGameTest {

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void bookKeyRoundTripsInNewLowercaseFormat(GameTestHelper helper) {
        // 1) Writer produces the level-first, all-lowercase form.
        ResourceLocation ench = ResourceLocation.fromNamespaceAndPath("minecraft", "feather_falling");
        TradeKey key = OfferFactory.buildSyntheticBookKey(ench, 3);
        helper.assertTrue(key.id().getPath().equals("book/3/minecraft/feather_falling"),
                Component.literal("new book key should be book/3/minecraft/feather_falling, got " + key.id().getPath()));

        // 2) Reader recovers the exact enchantment + level.
        OfferFactory.SyntheticBookKey parsed = OfferFactory.parseSyntheticBook(key);
        helper.assertTrue(parsed != null, Component.literal("new-format book key should parse"));
        helper.assertTrue(parsed.enchantmentId().equals(ench),
                Component.literal("parsed enchantment should be " + ench + ", got "
                        + (parsed == null ? "null" : parsed.enchantmentId())));
        helper.assertTrue(parsed.level() == 3,
                Component.literal("parsed level should be 3, got " + (parsed == null ? "?" : parsed.level())));

        // 3) Enchant paths may contain slashes (modded). Level-first keeps parsing unambiguous.
        ResourceLocation slashed = ResourceLocation.fromNamespaceAndPath("somemod", "deep/magic");
        OfferFactory.SyntheticBookKey parsedSlashed = OfferFactory.parseSyntheticBook(
                OfferFactory.buildSyntheticBookKey(slashed, 5));
        helper.assertTrue(parsedSlashed != null
                        && parsedSlashed.enchantmentId().equals(slashed)
                        && parsedSlashed.level() == 5,
                Component.literal("multi-segment enchant path should round-trip with its level"));

        // 4) Legacy bare form (no level marker) still reads as level 1.
        TradeKey legacy = new TradeKey(
                ResourceLocation.fromNamespaceAndPath("tradeoptimizer", "book/minecraft/sharpness"));
        OfferFactory.SyntheticBookKey parsedLegacy = OfferFactory.parseSyntheticBook(legacy);
        helper.assertTrue(parsedLegacy != null
                        && parsedLegacy.enchantmentId().equals(
                                ResourceLocation.fromNamespaceAndPath("minecraft", "sharpness"))
                        && parsedLegacy.level() == 1,
                Component.literal("legacy bare book key should parse as level 1"));

        helper.succeed();
    }
}
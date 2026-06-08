package com.tom.tradeoptimizer.trade;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;

/**
 * NeoForge port of the synthetic book-key format test. Lives in com.tom.tradeoptimizer.trade so it
 * can reach the package-private buildSyntheticBookKey / parseSyntheticBook / SyntheticBookKey (same
 * as the Fabric version). Registered from NeoForgeGameTests.
 */
public final class NeoForgeBookKeyTest {
    private NeoForgeBookKeyTest() {}

    public static void bookKeyRoundTripsInNewLowercaseFormat(GameTestHelper helper) {
        Identifier ench = Identifier.fromNamespaceAndPath("minecraft", "feather_falling");
        TradeKey key = OfferFactory.buildSyntheticBookKey(ench, 3);
        helper.assertTrue(key.id().getPath().equals("book/3/minecraft/feather_falling"),
                "new book key should be book/3/minecraft/feather_falling, got " + key.id().getPath());

        OfferFactory.SyntheticBookKey parsed = OfferFactory.parseSyntheticBook(key);
        helper.assertTrue(parsed != null, "new-format book key should parse");
        helper.assertTrue(parsed.enchantmentId().equals(ench),
                "parsed enchantment should be " + ench + ", got " + (parsed == null ? "null" : parsed.enchantmentId()));
        helper.assertTrue(parsed.level() == 3, "parsed level should be 3, got " + (parsed == null ? "?" : parsed.level()));

        Identifier slashed = Identifier.fromNamespaceAndPath("somemod", "deep/magic");
        OfferFactory.SyntheticBookKey parsedSlashed = OfferFactory.parseSyntheticBook(
                OfferFactory.buildSyntheticBookKey(slashed, 5));
        helper.assertTrue(parsedSlashed != null
                        && parsedSlashed.enchantmentId().equals(slashed)
                        && parsedSlashed.level() == 5,
                "multi-segment enchant path should round-trip with its level");

        TradeKey legacy = new TradeKey(
                Identifier.fromNamespaceAndPath("tradeoptimizer", "book/minecraft/sharpness"));
        OfferFactory.SyntheticBookKey parsedLegacy = OfferFactory.parseSyntheticBook(legacy);
        helper.assertTrue(parsedLegacy != null
                        && parsedLegacy.enchantmentId().equals(
                                Identifier.fromNamespaceAndPath("minecraft", "sharpness"))
                        && parsedLegacy.level() == 1,
                "legacy bare book key should parse as level 1");

        helper.succeed();
    }
}

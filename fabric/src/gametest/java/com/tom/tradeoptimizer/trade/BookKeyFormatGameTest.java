package com.tom.tradeoptimizer.trade;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;

/**
 * Guards the synthetic book-key encoding after the level-first migration.
 *
 * Book picks are stored as a synthetic Identifier that packs the enchantment + level. The old
 * form (book/&lt;ns&gt;/&lt;path&gt;/L&lt;level&gt;) used an uppercase 'L', which strict Identifier
 * validation rejects — it only ever worked because the live game is lenient, and it can't be
 * constructed in a headless test at all. The new form is level-first and all-lowercase
 * (book/&lt;level&gt;/&lt;ns&gt;/&lt;path&gt;), so it constructs everywhere. This test, finally able
 * to build the key in-process, verifies the writer's output and the reader's round-trip — including
 * a multi-slash (modded) enchant path and the oldest legacy bare form.
 *
 * Lives in package com.tom.tradeoptimizer.trade so it can reach the package-private
 * buildSyntheticBookKey / parseSyntheticBook / SyntheticBookKey. The intermediate uppercase-'L'
 * form is intentionally NOT exercised: the headless server rejects constructing that Identifier
 * (see project memory), and it's read-only legacy anyway.
 */
public class BookKeyFormatGameTest {

    @GameTest
    public void bookKeyRoundTripsInNewLowercaseFormat(GameTestHelper helper) {
        // 1) Writer produces the level-first, all-lowercase form — and, critically, CONSTRUCTS in
        //    the headless server (the old uppercase-'L' form threw IdentifierException here).
        Identifier ench = Identifier.fromNamespaceAndPath("minecraft", "feather_falling");
        TradeKey key = OfferFactory.buildSyntheticBookKey(ench, 3);
        helper.assertTrue(key.id().getPath().equals("book/3/minecraft/feather_falling"),
                "new book key should be book/3/minecraft/feather_falling, got " + key.id().getPath());

        // 2) Reader recovers the exact enchantment + level.
        OfferFactory.SyntheticBookKey parsed = OfferFactory.parseSyntheticBook(key);
        helper.assertTrue(parsed != null, "new-format book key should parse");
        helper.assertTrue(parsed.enchantmentId().equals(ench),
                "parsed enchantment should be " + ench + ", got "
                        + (parsed == null ? "null" : parsed.enchantmentId()));
        helper.assertTrue(parsed.level() == 3,
                "parsed level should be 3, got " + (parsed == null ? "?" : parsed.level()));

        // 3) Enchant paths may contain slashes (modded). Level-first keeps parsing unambiguous.
        Identifier slashed = Identifier.fromNamespaceAndPath("somemod", "deep/magic");
        OfferFactory.SyntheticBookKey parsedSlashed = OfferFactory.parseSyntheticBook(
                OfferFactory.buildSyntheticBookKey(slashed, 5));
        helper.assertTrue(parsedSlashed != null
                        && parsedSlashed.enchantmentId().equals(slashed)
                        && parsedSlashed.level() == 5,
                "multi-segment enchant path should round-trip with its level");

        // 4) Legacy bare form (no level marker) still reads as level 1 — back-compat for the oldest
        //    saves written before per-level expansion existed.
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

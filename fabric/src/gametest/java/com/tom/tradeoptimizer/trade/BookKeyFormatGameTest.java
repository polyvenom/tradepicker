package com.tom.tradeoptimizer.trade;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;

/**
 * Guards the synthetic book-key encoding, book/&lt;level&gt;/&lt;ns&gt;/&lt;path&gt;: the writer's
 * output and the reader's round-trip, including a multi-slash (modded) enchant path and the bare
 * pre-level form.
 *
 * In package com.tom.tradeoptimizer.trade to reach the package-private buildSyntheticBookKey /
 * parseSyntheticBook / SyntheticBookKey. The older book/&lt;ns&gt;/&lt;path&gt;/L&lt;level&gt; form is
 * not exercised: its uppercase 'L' can't be built as an Identifier on the headless server.
 */
public class BookKeyFormatGameTest {

    @GameTest
    public void bookKeyRoundTripsInNewLowercaseFormat(GameTestHelper helper) {
        // 1) Writer produces the level-first, all-lowercase form.
        Identifier ench = Identifier.fromNamespaceAndPath("minecraft", "feather_falling");
        TradeKey key = OfferFactory.buildSyntheticBookKey(ench, 3);
        helper.assertTrue(key.id().getPath().equals("book/3/minecraft/feather_falling"),
                "new book key should be book/3/minecraft/feather_falling, got " + key.id().getPath());

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

        // 4) The bare form (no level marker) reads as level 1, for the oldest saves.
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

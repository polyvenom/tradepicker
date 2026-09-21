package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.trade.TradeKey;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.UUID;

/**
 * Guards {@link ProfileController#firstUnfilledLevel} — the scan that decides which level the
 * picker opens for.
 *
 * The bug it protects against: the controller used to ask only whether the CURRENT level was
 * filled. Vanilla banks level-ups while a player is trading (the level-up is queued and only
 * applied once the trade screen closes), so two levels can land back to back. The picker then
 * opened for the top level, the one underneath stayed empty forever, and the villager finished at
 * max level with a pair of trades missing.
 *
 * Scanning from level 1 upward is what makes the catch-up chain work, so the "skips a gap in the
 * middle" case below is the one that actually failed before.
 */
public class LevelCatchUpGameTest {

    /** A gap below the current level is found first — this is the two-level-jump bug. */
    @GameTest
    public void gapBelowCurrentLevelIsPickedFirst(GameTestHelper helper) {
        VillagerProfile profile = VillagerProfile.fresh(UUID.randomUUID(), "minecraft:librarian");
        profile.setPicks(1, dummyPicks());
        profile.setPicks(2, dummyPicks());

        helper.assertValueEqual(ProfileController.firstUnfilledLevel(profile, 4), 3,
                "level with a gap below the current one");

        helper.succeed();
    }

    /** Nothing picked yet — start at the bottom. */
    @GameTest
    public void emptyProfileStartsAtLevelOne(GameTestHelper helper) {
        VillagerProfile profile = VillagerProfile.fresh(UUID.randomUUID(), "minecraft:librarian");

        helper.assertValueEqual(ProfileController.firstUnfilledLevel(profile, 3), 1,
                "first unfilled level of an empty profile");

        helper.succeed();
    }

    /** Every level accounted for reports -1, so the caller opens the merchant instead. */
    @GameTest
    public void fullyPickedProfileReportsNothingLeft(GameTestHelper helper) {
        VillagerProfile profile = VillagerProfile.fresh(UUID.randomUUID(), "minecraft:librarian");
        for (int lvl = 1; lvl <= 3; lvl++) profile.setPicks(lvl, dummyPicks());

        helper.assertValueEqual(ProfileController.firstUnfilledLevel(profile, 3), -1,
                "first unfilled level when every level is picked");

        helper.succeed();
    }

    /** Imported vanilla offers count as filled — an imported villager must not be re-picked. */
    @GameTest
    public void legacyOffersCountAsFilled(GameTestHelper helper) {
        VillagerProfile profile = VillagerProfile.fresh(UUID.randomUUID(), "minecraft:librarian");
        profile.setLegacy(1, List.of(dummyOffer()));
        profile.setPicks(2, dummyPicks());

        helper.assertValueEqual(ProfileController.firstUnfilledLevel(profile, 3), 3,
                "first unfilled level when level 1 is legacy");

        helper.succeed();
    }

    // -------------------------------------------------------------------------

    /** The scan only cares whether a level's list is non-empty, so the ids need not resolve. */
    private static List<TradeKey> dummyPicks() {
        return List.of(new TradeKey(Identifier.withDefaultNamespace("test_trade")));
    }

    private static MerchantOffer dummyOffer() {
        return new MerchantOffer(new ItemCost(Items.EMERALD), new ItemStack(Items.BREAD), 8, 1, 0.05f);
    }
}

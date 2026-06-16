package com.tom.tradeoptimizer.villager;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;

/**
 * Guards the legacy-offer bucketing in {@link ProfileController#importExistingOffers} — the
 * logic that splits a pre-existing villager's flat MerchantOffers list into per-level "legacy"
 * buckets when the mod first claims it.
 *
 * Two historical bugs this protects against (see the method's own javadoc): offers filed at the
 * WRONG level when a villager didn't match a flat 2-per-level grid, and offers past that grid
 * being DROPPED entirely. The fix walks each level's real trade-set size (capped at 2) and lets
 * the villager's current level act as a catch-all.
 *
 * Lives in package com.tom.tradeoptimizer.villager (not the usual ...gametest package) so it can
 * call the package-private importExistingOffers directly: the only public caller, onInteract, is
 * gated behind ServerPlayNetworking.canSend, which a headless mock player can't satisfy.
 *
 * Farmer is used because its level-1..4 trade pools all hold >2 trades, so every lower level's
 * cap is a stable 2 — and farmer trades are untouched by the gametest server's experimental Trade
 * Rebalance datapack.
 */
public class LegacyBucketingGameTest {

    @GameTest
    public void bucketsLegacyOffersPerLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();

        Villager villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(5));

        // Each case: a flat list of `size` offers imported as if owned by a farmer at
        // `merchantLevel`. expectedByLevel is indexed by level (slot 0 unused). Levels below the
        // current one take up to 2; the current (merchant) level is the catch-all that absorbs
        // everything left over — so even an oversized list (9 @ L3) loses nothing.
        checkCase(helper, level, villager, 1, 1, new int[]{0, 1});
        checkCase(helper, level, villager, 2, 1, new int[]{0, 2});
        checkCase(helper, level, villager, 3, 2, new int[]{0, 2, 1});
        checkCase(helper, level, villager, 5, 3, new int[]{0, 2, 2, 1});
        checkCase(helper, level, villager, 9, 3, new int[]{0, 2, 2, 5});

        helper.succeed();
    }

    private static void checkCase(GameTestHelper helper, ServerLevel level, Villager villager,
                                  int size, int merchantLevel, int[] expectedByLevel) {
        MerchantOffers existing = new MerchantOffers();
        for (int i = 0; i < size; i++) {
            // Distinct result counts make any ordering failure obvious in the logs.
            existing.add(new MerchantOffer(
                    new ItemCost(Items.WHEAT, 1),
                    new ItemStack(Items.EMERALD, i + 1),
                    12, 1, 0.05f));
        }

        VillagerProfile profile = VillagerProfile.fresh(villager.getUUID(), "minecraft:farmer");
        ProfileController.importExistingOffers(level, villager, profile, existing, merchantLevel);

        String tag = "size=" + size + " @ L" + merchantLevel;

        // 1) No offer is ever lost: the buckets together hold exactly the input count.
        int totalBucketed = 0;
        for (int lvl = 1; lvl <= merchantLevel; lvl++) totalBucketed += profile.legacyFor(lvl).size();
        helper.assertTrue(totalBucketed == size,
                tag + ": buckets hold " + totalBucketed + " offers but " + size
                        + " were imported (offer lost or duplicated)");

        // 2) Order + identity preserved: concatenating buckets level-ascending reproduces the
        //    original list, same instances in the same order — guards against mis-filing.
        List<MerchantOffer> reconstructed = new ArrayList<>();
        for (int lvl = 1; lvl <= merchantLevel; lvl++) reconstructed.addAll(profile.legacyFor(lvl));
        for (int k = 0; k < size; k++) {
            helper.assertTrue(reconstructed.get(k) == existing.get(k),
                    tag + ": offer at index " + k + " moved or reordered during bucketing");
        }

        // 3) Per-level split matches vanilla's grid: lower levels capped at 2, current level catch-all.
        for (int lvl = 1; lvl <= merchantLevel; lvl++) {
            int got = profile.legacyFor(lvl).size();
            helper.assertTrue(got == expectedByLevel[lvl],
                    tag + ": level " + lvl + " bucket has " + got + " offers, expected " + expectedByLevel[lvl]);
        }

        // 4) No buckets leak above the merchant level (max villager level is 5).
        for (int lvl = merchantLevel + 1; lvl <= 5; lvl++) {
            helper.assertTrue(profile.legacyFor(lvl).isEmpty(),
                    tag + ": unexpected bucket at level " + lvl + " (above the merchant level)");
        }
    }
}

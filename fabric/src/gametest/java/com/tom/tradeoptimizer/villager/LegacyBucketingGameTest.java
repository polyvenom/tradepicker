package com.tom.tradeoptimizer.villager;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;

public class LegacyBucketingGameTest {

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void bucketsLegacyOffersPerLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(level.registryAccess(), VillagerType.PLAINS)
                .withProfession(level.registryAccess(), VillagerProfession.FARMER)
                .withLevel(5));

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
            existing.add(new MerchantOffer(
                    new ItemCost(Items.WHEAT, 1),
                    new ItemStack(Items.EMERALD, i + 1),
                    12, 1, 0.05f));
        }

        VillagerProfile profile = VillagerProfile.fresh(villager.getUUID(), "minecraft:farmer");
        ProfileController.importExistingOffers(level, villager, profile, existing, merchantLevel);

        String tag = "size=" + size + " @ L" + merchantLevel;

        int totalBucketed = 0;
        for (int lvl = 1; lvl <= merchantLevel; lvl++) totalBucketed += profile.legacyFor(lvl).size();
        helper.assertTrue(totalBucketed == size,
                Component.literal(tag + ": buckets hold " + totalBucketed + " offers but " + size
                        + " were imported (offer lost or duplicated)"));

        List<MerchantOffer> reconstructed = new ArrayList<>();
        for (int lvl = 1; lvl <= merchantLevel; lvl++) reconstructed.addAll(profile.legacyFor(lvl));
        for (int k = 0; k < size; k++) {
            helper.assertTrue(reconstructed.get(k) == existing.get(k),
                    Component.literal(tag + ": offer at index " + k + " moved or reordered during bucketing"));
        }

        for (int lvl = 1; lvl <= merchantLevel; lvl++) {
            int got = profile.legacyFor(lvl).size();
            helper.assertTrue(got == expectedByLevel[lvl],
                    Component.literal(tag + ": level " + lvl + " bucket has " + got + " offers, expected " + expectedByLevel[lvl]));
        }

        for (int lvl = merchantLevel + 1; lvl <= 5; lvl++) {
            helper.assertTrue(profile.legacyFor(lvl).isEmpty(),
                    Component.literal(tag + ": unexpected bucket at level " + lvl + " (above the merchant level)"));
        }
    }
}
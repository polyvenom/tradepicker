package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.platform.Services;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * Guards {@link ProfileController#applyToVillager} and the profile store's dimension scoping
 * (#11, #12).
 *
 * In package com.tom.tradeoptimizer.villager to call the package-private applyToVillager: its
 * public callers sit behind Services.NETWORK.canSendOpenPicker, which a mock player can't satisfy.
 */
public class ProfilePersistenceGameTest {

    private static Villager spawnFarmerAtLevel1(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .setType(VillagerType.PLAINS)
                .setProfession(VillagerProfession.FARMER)
                .setLevel(1));
        return villager;
    }

    /**
     * #11: legacy offers are matched to live offers by VALUE (result + base cost), not identity: a
     * codec round trip (save + reload) hands back a fresh MerchantOffer, so identity would fall
     * through to the stored snapshot and reset the use-count (a free restock).
     *
     * Simulates the reload with two separate, value-equal instances for the "live" and "legacy" offer.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void legacyUseCountsSurviveReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmerAtLevel1(helper);

        MerchantOffer liveOffer = new MerchantOffer(
                new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD, 1), 16, 2, 0.05f);
        for (int i = 0; i < 5; i++) liveOffer.increaseUses();
        MerchantOffers liveOffers = new MerchantOffers();
        liveOffers.add(liveOffer);
        Services.PLATFORM.setVillagerOffers(villager, liveOffers);

        // Value-equal to liveOffer with 0 uses: what the codec decodes after a reload.
        MerchantOffer decodedSnapshot = new MerchantOffer(
                new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD, 1), 16, 2, 0.05f);
        VillagerProfile profile = VillagerProfile.fresh(villager.getUUID(), "minecraft:farmer");
        profile.setLegacy(1, List.of(decodedSnapshot));

        ProfileController.applyToVillager(level, villager, profile);

        check(helper, villager.getOffers().size() == 1,
                "rebuild produced " + villager.getOffers().size() + " offer(s), expected 1");
        check(helper, villager.getOffers().get(0).getUses() == 5,
                "legacy offer lost its use-count on rebuild (restock rollback)");

        helper.succeed();
    }

    /**
     * Pins the claim ORDER inside applyToVillager: legacy offers must claim their matching live
     * instance before picks match against the same pool, or a legacy offer equal to a regenerated
     * pick would lose its used instance and fall back to its 0-use copy (the #11 restock rollback).
     *
     * No picks are configured, to stay independent of trade-set contents. The live list also
     * carries an unrelated offer the profile doesn't reference, which must be dropped.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void aMatchingPickCannotStealTheLegacyInstance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmerAtLevel1(helper);

        MerchantOffer fiveUseOffer = new MerchantOffer(
                new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD, 1), 16, 2, 0.05f);
        for (int i = 0; i < 5; i++) fiveUseOffer.increaseUses();
        MerchantOffer unrelatedOffer = new MerchantOffer(
                new ItemCost(Items.CARROT, 22), new ItemStack(Items.EMERALD, 1), 16, 1, 0.05f);
        MerchantOffers liveOffers = new MerchantOffers();
        liveOffers.add(fiveUseOffer);
        liveOffers.add(unrelatedOffer);
        Services.PLATFORM.setVillagerOffers(villager, liveOffers);

        MerchantOffer decodedSnapshot = new MerchantOffer(
                new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD, 1), 16, 2, 0.05f);
        VillagerProfile profile = VillagerProfile.fresh(villager.getUUID(), "minecraft:farmer");
        profile.setLegacy(1, List.of(decodedSnapshot));
        // No picks anywhere in the profile.

        ProfileController.applyToVillager(level, villager, profile);

        MerchantOffers result = villager.getOffers();
        check(helper, result.size() == 1,
                "rebuild produced " + result.size() + " offer(s); the unrelated offer isn't in "
                        + "the profile and should have been dropped");
        check(helper, result.get(0).getUses() == 5, "legacy did not claim the live instance first");

        helper.succeed();
    }

    /**
     * #12: every profile lives on the OVERWORLD's data storage, and
     * {@link VillagerProfileState#get(ServerLevel)} resolves there whichever level asks, so a
     * villager keeps its profile through a portal.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void profileStoreIsSharedAcrossDimensions(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        ServerLevel nether = server.getLevel(Level.NETHER);

        if (nether == null) {
            // The headless server may not have the nether loaded: nothing to compare, not a failure.
            helper.succeed();
            return;
        }

        check(helper, VillagerProfileState.get(nether) == VillagerProfileState.get(overworld),
                "villager profiles are still per-dimension; a villager loses its profile through a portal");

        VillagerProfile written = VillagerProfile.fresh(UUID.randomUUID(), "minecraft:farmer");
        VillagerProfileState.get(nether).update(written);
        VillagerProfile readBack = VillagerProfileState.get(overworld).get(written.id());
        check(helper, readBack != null,
                "profile written through the nether-side handle is not visible through the overworld-side handle");

        helper.succeed();
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, message);
    }
}

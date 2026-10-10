package com.tom.tradeoptimizer.villager;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
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
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;

/**
 * Guards {@link ProfileController#applyToVillager} carry-over and the profile store's dimension
 * scoping (#11, #12).
 *
 * In package com.tom.tradeoptimizer.villager to call the package-private applyToVillager (its
 * public callers need a modded client).
 */
public class ProfilePersistenceGameTest {

    private static Villager spawnFarmerAtLevel1(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(1));
        return villager;
    }

    /**
     * #11: legacy offers must be matched to live offers by value (result + base cost), not identity:
     * a save/reload decodes fresh MerchantOffer instances, and an identity match would fall back to
     * the stored snapshot and reset the use-count (a free restock).
     *
     * The reload is simulated with two separate but value-equal instances.
     */
    @GameTest
    public void legacyUseCountsSurviveReload(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmerAtLevel1(helper);

        MerchantOffer liveOffer = new MerchantOffer(
                new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD, 1), 16, 2, 0.05f);
        for (int i = 0; i < 5; i++) liveOffer.increaseUses();
        MerchantOffers liveOffers = new MerchantOffers();
        liveOffers.add(liveOffer);
        villager.setOffers(liveOffers);

        // Value-equal to liveOffer with 0 uses: what the codec decodes after a reload.
        MerchantOffer decodedSnapshot = new MerchantOffer(
                new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD, 1), 16, 2, 0.05f);
        VillagerProfile profile = VillagerProfile.fresh(villager.getUUID(), "minecraft:farmer");
        profile.setLegacy(1, List.of(decodedSnapshot));

        ProfileController.applyToVillager(level, villager, profile);

        helper.assertTrue(villager.getOffers().size() == 1,
                "rebuild produced " + villager.getOffers().size() + " offer(s), expected 1");
        helper.assertTrue(villager.getOffers().get(0).getUses() == 5,
                "legacy offer lost its use-count on rebuild (restock rollback)");

        helper.succeed();
    }

    /**
     * Pins the claim order in applyToVillager: legacy offers claim their live instance before picks
     * match against the same pool. Otherwise a pick matching a legacy offer would take its used
     * instance and leave the 0-use stored copy (the #11 rollback, via ordering).
     *
     * The profile has only a legacy entry (independent of trade-set contents), and the live list
     * carries an unrelated offer that must be dropped.
     */
    @GameTest
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
        villager.setOffers(liveOffers);

        MerchantOffer decodedSnapshot = new MerchantOffer(
                new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD, 1), 16, 2, 0.05f);
        VillagerProfile profile = VillagerProfile.fresh(villager.getUUID(), "minecraft:farmer");
        profile.setLegacy(1, List.of(decodedSnapshot));

        ProfileController.applyToVillager(level, villager, profile);

        MerchantOffers result = villager.getOffers();
        helper.assertTrue(result.size() == 1,
                "rebuild produced " + result.size() + " offer(s); the unrelated offer isn't in "
                        + "the profile and should have been dropped");
        helper.assertTrue(result.get(0).getUses() == 5, "legacy did not claim the live instance first");

        helper.succeed();
    }

    /**
     * #12: every profile lives on the overworld's data storage, and
     * {@link VillagerProfileState#get(ServerLevel)} resolves there from any level, so a villager
     * keeps its profile through a portal.
     */
    @GameTest
    public void profileStoreIsSharedAcrossDimensions(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        ServerLevel nether = server.getLevel(Level.NETHER);

        if (nether == null) {
            // A headless gametest server may not have the nether loaded; nothing to compare.
            helper.succeed();
            return;
        }

        helper.assertTrue(VillagerProfileState.get(nether) == VillagerProfileState.get(overworld),
                "villager profiles are still per-dimension; a villager loses its profile through a portal");

        VillagerProfile written = VillagerProfile.fresh(UUID.randomUUID(), "minecraft:farmer");
        VillagerProfileState.get(nether).update(written);
        VillagerProfile readBack = VillagerProfileState.get(overworld).get(written.id());
        helper.assertTrue(readBack != null,
                "profile written through the nether-side handle is not visible through the overworld-side handle");

        helper.succeed();
    }
}

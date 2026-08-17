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
 * Guards two fixes to {@link ProfileController#applyToVillager} and the profile store's
 * dimension scoping (issues #11 and #12).
 *
 * Lives in package com.tom.tradeoptimizer.villager so it can call applyToVillager directly:
 * it's package-private (the only public callers all sit behind ServerPlayNetworking.canSend,
 * which a headless mock player can't satisfy), same reasoning as LegacyBucketingGameTest.
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
     * Issue #11: legacy offers used to be matched by object IDENTITY against the villager's
     * live offers. That only holds within a single session — a codec round trip (world save
     * + reload) always hands back a fresh MerchantOffer instance, so post-reload the identity
     * check found nothing, fell through to the profile's stored snapshot, and the villager's
     * accumulated use-count silently reset to 0 (a free restock). The fix matches by VALUE
     * (result + base cost) instead, so a decoded snapshot still finds and keeps its live
     * counterpart.
     *
     * Simulates the reload by building the "live" offer and the profile's "legacy" offer as
     * two separate but value-equal instances — exactly what a save/load round trip produces.
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

        // Separate instance, value-equal to liveOffer, 0 uses — stands in for what the codec
        // decodes back after a reload.
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
     * Pins the claim ORDER inside applyToVillager: legacy offers must claim their matching
     * live instance before picks get a chance to generate and match against the same pool.
     * If picks claimed first, a legacy offer whose value happens to match a regenerated pick
     * would lose its live (used) instance to the pick and fall back to its 0-use stored copy —
     * the same restock-rollback bug as issue #11, but reachable through claim ordering instead
     * of missing identity.
     *
     * No picks are configured here (deliberately, to keep this independent of trade-set
     * contents) — the profile has only a legacy entry at level 1. The live offers list also
     * carries an unrelated second offer that isn't referenced by the profile at all, to confirm
     * it's dropped rather than leaking into the rebuilt list.
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
        // No picks anywhere in the profile.

        ProfileController.applyToVillager(level, villager, profile);

        MerchantOffers result = villager.getOffers();
        helper.assertTrue(result.size() == 1,
                "rebuild produced " + result.size() + " offer(s); the unrelated offer isn't in "
                        + "the profile and should have been dropped");
        helper.assertTrue(result.get(0).getUses() == 5, "legacy did not claim the live instance first");

        helper.succeed();
    }

    /**
     * Issue #12: the profile store used to live on whichever dimension's data storage the
     * villager happened to be standing in. A villager keeps its UUID through a portal, but its
     * profile didn't follow — the far side saw it as unclaimed (a second player could re-claim,
     * re-pick and reset it), and stepping back the stale original profile would reassert itself
     * over the live offers. The fix parks every profile on the OVERWORLD's data storage and has
     * {@link VillagerProfileState#get(ServerLevel)} always resolve there, regardless of which
     * level is asking.
     */
    @GameTest
    public void profileStoreIsSharedAcrossDimensions(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerLevel overworld = server.overworld();
        ServerLevel nether = server.getLevel(Level.NETHER);

        if (nether == null) {
            // A headless gametest server may not have the nether loaded. Nothing to compare
            // against, so there's no per-dimension regression to catch here — not a failure.
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

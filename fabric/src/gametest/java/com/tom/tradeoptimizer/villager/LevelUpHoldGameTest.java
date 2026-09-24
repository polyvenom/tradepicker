package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.TradeSet;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Guards the held level-up: a managed villager's rank must not move until the player has picked
 * that rank's trades, and then it must move as part of the same submit.
 *
 * VillagerLevelUpMixin cancels vanilla's increaseMerchantCareer for managed villagers, so nothing
 * else in the game will ever raise the rank. If the grant in onPickerSubmit regressed, a villager
 * would sit at its old rank forever with the player's picks piling up on a level it never reached
 * — so the "rank actually rises" assertion below is the load-bearing one.
 */
public class LevelUpHoldGameTest {

    /** Vanilla's own threshold: enough XP banked and the rank still allowed to rise. */
    @GameTest
    public void readinessMatchesVanillaThreshold(GameTestHelper helper) {
        Villager villager = spawnFarmer(helper, 1);

        villager.setVillagerXp(0);
        helper.assertFalse(ProfileController.isDueToLevelUp(villager),
                "a villager with no XP is not due to level up");

        villager.setVillagerXp(VillagerData.getMaxXpPerLevel(1));
        helper.assertTrue(ProfileController.isDueToLevelUp(villager),
                "a villager at the level-1 XP bar is due to level up");

        helper.succeed();
    }

    /** A villager at max rank is never due, however much XP it banks. */
    @GameTest
    public void maxRankIsNeverDue(GameTestHelper helper) {
        Villager villager = spawnFarmer(helper, 5);
        villager.setVillagerXp(Integer.MAX_VALUE / 2);

        helper.assertFalse(ProfileController.isDueToLevelUp(villager),
                "a master villager can't level up regardless of XP");

        helper.succeed();
    }

    /** Picking for the pending rank grants it — rank and trades land together. */
    @GameTest
    public void submittingThePendingLevelGrantsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();

        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        helper.assertValueEqual(villager.getVillagerData().level(), 1,
                "rank after picking level 1");

        // Bank the XP vanilla would have levelled on. The mixin holds the rank, so it only moves
        // when the player picks for it.
        villager.setVillagerXp(VillagerData.getMaxXpPerLevel(1));
        helper.assertValueEqual(villager.getVillagerData().level(), 1,
                "rank while the level-up is held");

        ProfileController.onPickerSubmit(owner, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        helper.assertValueEqual(villager.getVillagerData().level(), 2,
                "rank after picking the pending level");

        VillagerProfile profile = VillagerProfileState.get(level).get(villagerId);
        helper.assertTrue(profile != null && profile.isFilled(2),
                "the pending level's picks must be stored");

        helper.succeed();
    }

    /** Without the XP for it, a submit one rank ahead is rejected and the rank stays put. */
    @GameTest
    public void submittingAheadWithoutXpIsRejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();

        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        villager.setVillagerXp(0);

        ProfileController.onPickerSubmit(owner, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        helper.assertValueEqual(villager.getVillagerData().level(), 1,
                "rank after a submit that hadn't earned the level");

        VillagerProfile profile = VillagerProfileState.get(level).get(villagerId);
        helper.assertTrue(profile != null && !profile.isFilled(2),
                "picks for an unearned level must not be stored");

        helper.succeed();
    }

    // -------------------------------------------------------------------------

    private static Villager spawnFarmer(GameTestHelper helper, int villagerLevel) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(villagerLevel));
        return villager;
    }

    private static List<TradeKey> firstTwoPicks(ServerLevel level, Villager villager,
                                                int merchantLevel, GameTestHelper helper) {
        ResourceKey<TradeSet> tradeSetKey =
                villager.getVillagerData().profession().value().getTrades(merchantLevel);
        helper.assertTrue(tradeSetKey != null, "farmer level " + merchantLevel + " should have a trade set");
        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, tradeSetKey);
        helper.assertTrue(available.size() >= 2,
                "farmer level " + merchantLevel + " needs >=2 trade options (got " + available.size() + ")");
        List<TradeKey> picks = new ArrayList<>();
        picks.add(available.get(0).key());
        picks.add(available.get(1).key());
        return picks;
    }
}

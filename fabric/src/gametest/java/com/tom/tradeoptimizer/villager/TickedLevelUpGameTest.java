package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.gametest.TestPlayers;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.TradeSet;

import java.util.ArrayList;
import java.util.List;

/**
 * The level-up hold, checked against the running game.
 *
 * Vanilla queues a level-up: the trade that crosses the XP bar arms a 40-tick timer, which only
 * drains while the villager isn't trading, then calls increaseMerchantCareer. These tests arm the
 * timer as a real trade does and tick the server past it, so they fail if the mixin stops applying
 * (renamed target, changed signature, dropped from the config).
 *
 * The first test is the control: it proves the scenario levels a villager here, which gives "the
 * managed one didn't level" meaning.
 */
public class TickedLevelUpGameTest {

    /** Comfortably past vanilla's 40-tick timer. */
    private static final int AFTER_QUEUED_LEVEL_UP = 80;

    /** A villager the mod has never touched still levels up exactly as vanilla does. */
    @GameTest(maxTicks = 200)
    public void unmanagedVillagerStillLevelsUpOnItsOwn(GameTestHelper helper) {
        Villager villager = spawnFarmer(helper, 1);
        armQueuedLevelUp(villager, new MerchantOffer(
                new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD), 16, 2, 0.05f));

        helper.runAtTickTime(AFTER_QUEUED_LEVEL_UP, () -> {
            helper.assertValueEqual(villager.getVillagerData().level(), 2,
                    "an unmanaged villager's rank after its queued level-up");
            helper.succeed();
        });
    }

    /** A managed villager's queued level-up is held: the rank waits for the player's picks. */
    @GameTest(maxTicks = 200)
    public void managedVillagerIsHeldThroughTheQueuedLevelUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        ServerPlayer player = TestPlayers.near(helper);

        ProfileController.onPickerSubmit(player, villager.getUUID(), 1, firstTwoPicks(level, villager, 1));
        stopTrading(player, villager);
        armQueuedLevelUp(villager, villager.getOffers().get(0));

        helper.runAtTickTime(AFTER_QUEUED_LEVEL_UP, () -> {
            helper.assertValueEqual(villager.getVillagerData().level(), 1,
                    "a managed villager's rank after the queued level-up fired");
            helper.assertTrue(ProfileController.isDueToLevelUp(villager),
                    "the held level-up must still be due, so the next right-click offers it");
            helper.succeed();
        });
    }

    /**
     * Reset from the trade screen leaves the villager on Novice even with a level-up queued by the
     * last trade. The Reset button lives on the merchant screen, so the villager is mid-trade,
     * which freezes the timer long enough to survive into the reset; unheld, it would fire two
     * seconds later and land the villager on level 2, skipping its Novice picks.
     */
    @GameTest(maxTicks = 200)
    public void resetStaysNoviceThroughAQueuedLevelUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        ServerPlayer player = TestPlayers.near(helper);

        ProfileController.onPickerSubmit(player, villager.getUUID(), 1, firstTwoPicks(level, villager, 1));
        stopTrading(player, villager);
        armQueuedLevelUp(villager, villager.getOffers().get(0));

        // Back on the merchant screen, then Reset.
        villager.setTradingPlayer(player);
        ProfileController.onReset(player, villager.getUUID());
        helper.assertValueEqual(villager.getVillagerData().level(), 1, "rank straight after reset");

        helper.runAtTickTime(AFTER_QUEUED_LEVEL_UP, () -> {
            helper.assertValueEqual(villager.getVillagerData().level(), 1,
                    "a reset villager's rank after the queued level-up fired");
            helper.succeed();
        });
    }

    // -------------------------------------------------------------------------

    /**
     * Arm vanilla's queued level-up as a real trade does: sit on the XP bar, then notify the trade
     * so rewardTradeXp schedules it. No trading player, so the timer drains.
     */
    private static void armQueuedLevelUp(Villager villager, MerchantOffer offer) {
        villager.setVillagerXp(VillagerData.getMaxXpPerLevel(villager.getVillagerData().level()));
        villager.notifyTrade(offer);
    }

    private static void stopTrading(ServerPlayer player, Villager villager) {
        player.closeContainer();
        villager.setTradingPlayer(null);
    }

    private static Villager spawnFarmer(GameTestHelper helper, int villagerLevel) {
        var registries = helper.getLevel().registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(villagerLevel));
        return villager;
    }

    private static List<TradeKey> firstTwoPicks(ServerLevel level, Villager villager, int merchantLevel) {
        ResourceKey<TradeSet> tradeSet =
                villager.getVillagerData().profession().value().getTrades(merchantLevel);
        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, tradeSet);
        List<TradeKey> picks = new ArrayList<>();
        picks.add(available.get(0).key());
        picks.add(available.get(1).key());
        return picks;
    }
}

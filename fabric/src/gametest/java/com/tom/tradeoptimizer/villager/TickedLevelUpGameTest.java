package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.gametest.MockPlayers;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.List;

/**
 * The level-up hold, checked against the game actually running rather than against its logic.
 *
 * Vanilla queues a level-up: the trade that crosses the XP bar arms a 40-tick timer, which only
 * drains while the villager isn't trading, and then calls increaseMerchantCareer. The other
 * level-up tests call the mod's methods directly; these arm the timer the way a real trade does
 * and let the server tick past it, so they fail if the mixin ever stops applying — a renamed
 * target, a changed signature, a refactor that drops it from the config.
 *
 * The first test is the control. It proves the scenario really does level a villager in this
 * environment, which is what makes "the managed one didn't level" mean something.
 */
public class TickedLevelUpGameTest {

    /** Comfortably past vanilla's 40-tick timer. */
    private static final int AFTER_QUEUED_LEVEL_UP = 80;

    /** A villager the mod has never touched still levels up exactly as vanilla does. */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 200)
    public void unmanagedVillagerStillLevelsUpOnItsOwn(GameTestHelper helper) {
        Villager villager = spawnFarmer(helper, 1);
        armQueuedLevelUp(villager, new MerchantOffer(
                new ItemCost(Items.WHEAT, 20), new ItemStack(Items.EMERALD), 16, 2, 0.05f));

        helper.runAtTickTime(AFTER_QUEUED_LEVEL_UP, () -> {
            helper.assertValueEqual(villager.getVillagerData().getLevel(), 2,
                    "an unmanaged villager's rank after its queued level-up");
            helper.succeed();
        });
    }

    /** A managed villager's queued level-up is held: the rank waits for the player's picks. */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 200)
    public void managedVillagerIsHeldThroughTheQueuedLevelUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        ServerPlayer player = MockPlayers.near(helper);

        ProfileController.onPickerSubmit(player, villager.getUUID(), 1, firstTwoPicks(level, villager, 1));
        stopTrading(player, villager);
        armQueuedLevelUp(villager, villager.getOffers().get(0));

        helper.runAtTickTime(AFTER_QUEUED_LEVEL_UP, () -> {
            helper.assertValueEqual(villager.getVillagerData().getLevel(), 1,
                    "a managed villager's rank after the queued level-up fired");
            helper.assertTrue(ProfileController.isDueToLevelUp(villager),
                    "the held level-up must still be due, so the next right-click offers it");
            helper.succeed();
        });
    }

    /**
     * Reset from the trade screen leaves the villager on Novice, even with a level-up queued by
     * the last trade before the click. The Reset button lives on the merchant screen, so the
     * villager is always mid-trade at that moment — which is exactly what freezes the timer long
     * enough for it to survive into the reset.
     *
     * This was the parked fix/reset-levelup bug: the queued level-up fired two seconds after the
     * reset and landed the villager on level 2, skipping its Novice picks for good. The level-up
     * hold covers it without that branch's dedicated fix.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty", timeoutTicks = 200)
    public void resetStaysNoviceThroughAQueuedLevelUp(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        ServerPlayer player = MockPlayers.near(helper);

        ProfileController.onPickerSubmit(player, villager.getUUID(), 1, firstTwoPicks(level, villager, 1));
        stopTrading(player, villager);
        armQueuedLevelUp(villager, villager.getOffers().get(0));

        // Back on the merchant screen, then Reset — the order a player actually does it in.
        villager.setTradingPlayer(player);
        ProfileController.onReset(player, villager.getUUID());
        helper.assertValueEqual(villager.getVillagerData().getLevel(), 1, "rank straight after reset");

        helper.runAtTickTime(AFTER_QUEUED_LEVEL_UP, () -> {
            helper.assertValueEqual(villager.getVillagerData().getLevel(), 1,
                    "a reset villager's rank after the queued level-up fired");
            helper.succeed();
        });
    }

    // -------------------------------------------------------------------------

    /**
     * Arm vanilla's queued level-up the way a real trade does: sit on the XP bar, then notify the
     * trade so rewardTradeXp schedules it. No trading player, so the timer is free to drain.
     */
    private static void armQueuedLevelUp(Villager villager, MerchantOffer offer) {
        villager.setVillagerXp(VillagerData.getMaxXpPerLevel(villager.getVillagerData().getLevel()));
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
                .setType(VillagerType.PLAINS)
                .setProfession(VillagerProfession.FARMER)
                .setLevel(villagerLevel));
        return villager;
    }

    private static List<TradeKey> firstTwoPicks(ServerLevel level, Villager villager, int merchantLevel) {
        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, merchantLevel);
        List<TradeKey> picks = new ArrayList<>();
        picks.add(available.get(0).key());
        picks.add(available.get(1).key());
        return picks;
    }
}

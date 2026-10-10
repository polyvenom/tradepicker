package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import com.tom.tradeoptimizer.villager.MerchantAccess;
import com.tom.tradeoptimizer.villager.ProfileController;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.item.trading.TradeSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Guards reputation pricing. The mod opens the merchant manually, so ProfileController.openMerchant
 * re-invokes Villager.updateSpecialPrices(player) (writing -floor(reputation × priceMultiplier)
 * into each offer's specialPriceDiff) on every open. Two failure modes:
 *
 *   1. Wipe-on-reopen: the client sends two interact packets per right-click, and the second
 *      run's stale-menu teardown calls setTradingPlayer(null) -> resetSpecialPrices(), destroying
 *      any discount refreshed before it. The refresh must happen inside openMerchant, after the
 *      teardown. Re-opening here deliberately doesn't close the previous menu (mock players never
 *      send close packets), reproducing the double-fire's stale-menu state.
 *
 *   2. Stacking: updateSpecialPrices accumulates, so without the reset before apply in
 *      refreshSpecialPrices, re-opens would compound the discount.
 *
 * Farmer is used for its flat trades, and because trade_rebalance (always on in game tests, see
 * OwnershipGateGameTest) overrides only armorer and librarian.
 */
public class ReputationPricingGameTest {

    @GameTest
    public void reputationDiscountSurvivesReopenAndDoesNotStack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        UUID villagerId = villager.getUUID();
        ServerPlayer player = TestPlayers.near(helper);

        // 1) Pick two level-1 trades, building the live offers.
        List<TradeKey> picks = firstTwoPicks(level, villager, helper);
        ProfileController.onPickerSubmit(player, villagerId, 1, picks);

        MerchantOffers offers = villager.getOffers();
        helper.assertTrue(offers != null && offers.size() >= 2,
                "expected the two picked level-1 offers after submit");

        // No reputation yet: no discount.
        for (MerchantOffer offer : offers) {
            helper.assertTrue(offer.getSpecialPriceDiff() == 0,
                    "no-reputation player should see specialPriceDiff 0 (got "
                            + offer.getSpecialPriceDiff() + ")");
        }

        // 2) Give the player reputation via gossip, as vanilla does.
        villager.getGossips().add(player.getUUID(), GossipType.MAJOR_POSITIVE, 20);
        int reputation = villager.getPlayerReputation(player);
        helper.assertTrue(reputation > 0,
                "gossip should yield positive reputation (got " + reputation + ")");

        // Re-open without closing the previous menu. Its teardown resets special prices mid-open,
        // so the discount must be written after that or the client's offers carry diff 0.
        MerchantAccess.open(player, villager);

        offers = villager.getOffers();
        boolean anyDiscount = false;
        for (MerchantOffer offer : offers) {
            int expected = -(int) Math.floor(reputation * offer.getPriceMultiplier());
            helper.assertTrue(offer.getSpecialPriceDiff() == expected,
                    "reputation " + reputation + " × multiplier " + offer.getPriceMultiplier()
                            + " should give specialPriceDiff " + expected + " (got "
                            + offer.getSpecialPriceDiff() + ") — a 0 here means the stale-menu "
                            + "teardown wiped the discount after it was applied");
            if (expected < 0) anyDiscount = true;
        }
        helper.assertTrue(anyDiscount,
                "at least one picked farmer offer should carry a visible reputation discount");

        // 3) Two more no-close re-opens must not stack the diff, and a clean close/reopen must restore it.
        List<Integer> before = diffs(offers);
        MerchantAccess.open(player, villager);
        MerchantAccess.open(player, villager);
        List<Integer> after = diffs(villager.getOffers());
        helper.assertTrue(before.equals(after),
                "discount changed across no-close re-opens: " + before + " -> " + after);

        player.closeContainer(); // vanilla resets special prices when trading stops
        MerchantAccess.open(player, villager);
        helper.assertTrue(before.equals(diffs(villager.getOffers())),
                "discount not restored after a clean close/reopen cycle");

        helper.succeed();
    }

    /**
     * Discounts are per-player state on shared offers: after a reputable player's open, a
     * reputation-less op re-opening the same villager must see every diff back at zero.
     */
    @GameTest
    public void discountDoesNotLeakToReputationlessPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = TestPlayers.near(helper);

        List<TradeKey> picks = firstTwoPicks(level, villager, helper);
        ProfileController.onPickerSubmit(owner, villagerId, 1, picks);
        villager.getGossips().add(owner.getUUID(), GossipType.MAJOR_POSITIVE, 20);
        MerchantAccess.open(owner, villager);

        boolean anyDiscount = false;
        for (MerchantOffer offer : villager.getOffers()) {
            if (offer.getSpecialPriceDiff() < 0) anyDiscount = true;
        }
        helper.assertTrue(anyDiscount, "precondition: the reputable owner should have a discount");

        // A second, reputation-less player, made an op as in OwnershipGateGameTest.
        ServerPlayer stranger = TestPlayers.near(helper);
        level.getServer().getPlayerList().op(stranger.nameAndId(),
                Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
        owner.closeContainer(); // the owner is done trading
        MerchantAccess.open(stranger, villager);

        for (MerchantOffer offer : villager.getOffers()) {
            helper.assertTrue(offer.getSpecialPriceDiff() == 0,
                    "reputation-less player inherited a stale discount (diff "
                            + offer.getSpecialPriceDiff() + ")");
        }

        helper.succeed();
    }

    private static List<Integer> diffs(MerchantOffers offers) {
        List<Integer> out = new ArrayList<>(offers.size());
        for (MerchantOffer offer : offers) out.add(offer.getSpecialPriceDiff());
        return out;
    }

    private static Villager spawnFarmer(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(1));
        return villager;
    }

    private static List<TradeKey> firstTwoPicks(ServerLevel level, Villager villager, GameTestHelper helper) {
        ResourceKey<TradeSet> tradeSetKey =
                villager.getVillagerData().profession().value().getTrades(1);
        helper.assertTrue(tradeSetKey != null, "farmer level 1 should have a trade set");
        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, tradeSetKey);
        helper.assertTrue(available.size() >= 2,
                "farmer level 1 needs >=2 trade options (got " + available.size() + ")");
        List<TradeKey> picks = new ArrayList<>();
        picks.add(available.get(0).key());
        picks.add(available.get(1).key());
        return picks;
    }
}

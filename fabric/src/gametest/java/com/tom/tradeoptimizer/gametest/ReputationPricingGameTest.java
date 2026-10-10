package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import com.tom.tradeoptimizer.villager.MerchantAccess;
import com.tom.tradeoptimizer.villager.ProfileController;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reputation pricing. The mod opens the merchant manually instead of via vanilla's startTrading,
 * so ProfileController.openMerchant re-invokes vanilla's discount pass
 * (Villager.updateSpecialPrices(player), which writes -floor(reputation × priceMultiplier) into
 * each offer's specialPriceDiff) on every open. Two failure modes are pinned:
 *
 *   1. WIPE-ON-REOPEN: the client sends two interact packets per right-click, so the open path
 *      runs twice. The second run's stale-menu teardown fires setTradingPlayer(null) ->
 *      resetSpecialPrices(), destroying a refresh done BEFORE it, so the refresh must happen
 *      inside openMerchant after the teardown. Re-opening here deliberately does NOT close the
 *      previous menu (mock players never send close packets), reproducing that stale-menu state.
 *
 *   2. STACKING: updateSpecialPrices accumulates, so without the reset-before-apply in
 *      refreshSpecialPrices, re-opens compound the discount.
 *
 * Farmer is used for its flat, biome-independent trades, and because trade_rebalance — always
 * on in game tests, see OwnershipGateGameTest — overrides only armorer and librarian.
 */
public class ReputationPricingGameTest {

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void reputationDiscountSurvivesReopenAndDoesNotStack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        UUID villagerId = villager.getUUID();
        ServerPlayer player = MockPlayers.near(helper);

        // 1) Pick two level-1 trades — builds the live offers through the real entry point.
        List<TradeKey> picks = firstTwoPicks(level, villager, helper);
        ProfileController.onPickerSubmit(player, villagerId, 1, picks);

        MerchantOffers offers = villager.getOffers();
        check(helper, offers != null && offers.size() >= 2,
                "expected the two picked level-1 offers after submit");

        // No reputation yet — the refresh ran but must have written no discount.
        for (MerchantOffer offer : offers) {
            check(helper, offer.getSpecialPriceDiff() == 0,
                    "no-reputation player should see specialPriceDiff 0 (got "
                            + offer.getSpecialPriceDiff() + ")");
        }

        // 2) Give the player curing-grade reputation the same way vanilla does (gossip).
        villager.getGossips().add(player.getUUID(), GossipType.MAJOR_POSITIVE, 20);
        int reputation = villager.getPlayerReputation(player);
        check(helper, reputation > 0,
                "gossip should yield positive reputation (got " + reputation + ")");

        // Re-open WITHOUT closing the previous menu. The stale MerchantMenu's teardown resets
        // special prices mid-open; the discount must be written after that.
        MerchantAccess.open(player, villager);

        offers = villager.getOffers();
        boolean anyDiscount = false;
        for (MerchantOffer offer : offers) {
            int expected = -(int) Math.floor(reputation * offer.getPriceMultiplier());
            check(helper, offer.getSpecialPriceDiff() == expected,
                    "reputation " + reputation + " × multiplier " + offer.getPriceMultiplier()
                            + " should give specialPriceDiff " + expected + " (got "
                            + offer.getSpecialPriceDiff() + ") — a 0 here means the stale-menu "
                            + "teardown wiped the discount after it was applied");
            if (expected < 0) anyDiscount = true;
        }
        check(helper, anyDiscount,
                "at least one picked farmer offer should carry a visible reputation discount");

        // 3) Two more no-close re-opens: the diff must hold steady, not stack — and a
        //    properly closed session must come back discounted too.
        List<Integer> before = diffs(offers);
        MerchantAccess.open(player, villager);
        MerchantAccess.open(player, villager);
        List<Integer> after = diffs(villager.getOffers());
        check(helper, before.equals(after),
                "discount changed across no-close re-opens: " + before + " -> " + after);

        player.closeContainer(); // vanilla resets special prices when trading stops
        MerchantAccess.open(player, villager);
        check(helper, before.equals(diffs(villager.getOffers())),
                "discount not restored after a clean close/reopen cycle");

        helper.succeed();
    }

    /**
     * Discounts are per-player state on shared offers: after a reputable player's open wrote
     * discounts, an op with no reputation re-opens the SAME villager and every diff must return
     * to zero (two players trading in sequence).
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void discountDoesNotLeakToReputationlessPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = MockPlayers.near(helper);

        List<TradeKey> picks = firstTwoPicks(level, villager, helper);
        ProfileController.onPickerSubmit(owner, villagerId, 1, picks);
        villager.getGossips().add(owner.getUUID(), GossipType.MAJOR_POSITIVE, 20);
        MerchantAccess.open(owner, villager);

        boolean anyDiscount = false;
        for (MerchantOffer offer : villager.getOffers()) {
            if (offer.getSpecialPriceDiff() < 0) anyDiscount = true;
        }
        check(helper, anyDiscount, "precondition: the reputable owner should have a discount");

        // A second, reputation-less player opens the same villager. Ops bypass the ownership gate,
        // so promote the mock player through the real op list, as OwnershipGateGameTest does.
        ServerPlayer stranger = MockPlayers.near(helper);
        MockPlayers.op(stranger);
        owner.closeContainer(); // the owner is done trading
        MerchantAccess.open(stranger, villager);

        for (MerchantOffer offer : villager.getOffers()) {
            check(helper, offer.getSpecialPriceDiff() == 0,
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
        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, 1);
        check(helper, available.size() >= 2,
                "farmer level 1 needs >=2 trade options (got " + available.size() + ")");
        List<TradeKey> picks = new ArrayList<>();
        picks.add(available.get(0).key());
        picks.add(available.get(1).key());
        return picks;
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, Component.literal(message));
    }
}

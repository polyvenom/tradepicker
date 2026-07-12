package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
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
 * Sanity net for reputation pricing (the v1.0.1 fix, restructured in v1.0.5).
 *
 * The mod opens the merchant manually (setTradingPlayer + openTradingScreen) instead of
 * vanilla's startTrading, so vanilla's discount pass — Villager.updateSpecialPrices(player),
 * which writes -floor(reputation × priceMultiplier) into each offer's specialPriceDiff —
 * has to be re-invoked by ProfileController.refreshSpecialPrices on every open path. If any
 * path forgets it, players stop getting curing / gossip discounts; if the reset-before-apply
 * is lost, discounts STACK on every re-open instead. This test pins both directions:
 *
 *   1. a player with positive gossip reputation gets a negative specialPriceDiff whose
 *      magnitude matches vanilla's formula on every offer with a non-zero multiplier;
 *   2. re-opening (re-applying) does not accumulate the discount;
 *   3. a reputation-less player on the same villager gets no discount (the refresh is
 *      per-player, not baked into the stored offers).
 *
 * Farmer is used for its flat, biome-independent trades (unaffected by the gametest
 * server's experimental Trade Rebalance datapack).
 */
public class ReputationPricingGameTest {

    @GameTest
    public void reputationDiscountAppliesAndDoesNotStack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        UUID villagerId = villager.getUUID();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        // 1) Pick two level-1 trades — builds the live offers through the real entry point.
        List<TradeKey> picks = firstTwoPicks(level, villager, helper);
        ProfileController.onPickerSubmit(player, villagerId, 1, picks);

        MerchantOffers offers = villager.getOffers();
        helper.assertTrue(offers != null && offers.size() >= 2,
                "expected the two picked level-1 offers after submit");

        // No reputation yet — the refresh ran but must have written no discount.
        for (MerchantOffer offer : offers) {
            helper.assertTrue(offer.getSpecialPriceDiff() == 0,
                    "no-reputation player should see specialPriceDiff 0 (got "
                            + offer.getSpecialPriceDiff() + ")");
        }

        // 2) Give the player curing-grade reputation the same way vanilla does (gossip).
        villager.getGossips().add(player.getUUID(), GossipType.MAJOR_POSITIVE, 20);
        int reputation = villager.getPlayerReputation(player);
        helper.assertTrue(reputation > 0,
                "gossip should yield positive reputation (got " + reputation + ")");

        // Re-run the open flow (same picks, same owner) — this is applyToVillager +
        // refreshSpecialPrices, the exact code path a right-click open goes through.
        // Close the merchant menu first, like a real client does before it can click the
        // villager again: a mock player never sends the close packet, and re-opening over a
        // stale server-side MerchantMenu makes openMenu tear it down mid-open, wiping the
        // just-applied diffs (the same stale-session hazard onReset documents).
        player.closeContainer();
        ProfileController.onPickerSubmit(player, villagerId, 1, picks);

        offers = villager.getOffers();
        boolean anyDiscount = false;
        for (MerchantOffer offer : offers) {
            int expected = -(int) Math.floor(reputation * offer.getPriceMultiplier());
            helper.assertTrue(offer.getSpecialPriceDiff() == expected,
                    "reputation " + reputation + " × multiplier " + offer.getPriceMultiplier()
                            + " should give specialPriceDiff " + expected + " (got "
                            + offer.getSpecialPriceDiff() + ")");
            if (expected < 0) anyDiscount = true;
        }
        helper.assertTrue(anyDiscount,
                "at least one picked farmer offer should carry a visible reputation discount");

        // 3) Re-open twice more: the diff must hold steady, not stack. updateSpecialPrices
        //    accumulates, so this fails if the reset-before-apply is ever dropped.
        List<Integer> before = diffs(offers);
        player.closeContainer();
        ProfileController.onPickerSubmit(player, villagerId, 1, picks);
        player.closeContainer();
        ProfileController.onPickerSubmit(player, villagerId, 1, picks);
        List<Integer> after = diffs(villager.getOffers());
        helper.assertTrue(before.equals(after),
                "discount stacked across re-opens: " + before + " -> " + after);

        helper.succeed();
    }

    /**
     * Discounts are per-player state on shared offers: after a reputable player's open wrote
     * discounts, an op (bypasses the ownership gate) with no reputation re-opens the SAME
     * villager and every diff must return to zero — the previous player's discount must not
     * leak. This mirrors two players trading with one villager in sequence.
     */
    @GameTest
    public void discountDoesNotLeakToReputationlessPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = helper.makeMockServerPlayerInLevel();

        List<TradeKey> picks = firstTwoPicks(level, villager, helper);
        ProfileController.onPickerSubmit(owner, villagerId, 1, picks);
        villager.getGossips().add(owner.getUUID(), GossipType.MAJOR_POSITIVE, 20);
        owner.closeContainer(); // mock players never send the close packet themselves
        ProfileController.onPickerSubmit(owner, villagerId, 1, picks);

        boolean anyDiscount = false;
        for (MerchantOffer offer : villager.getOffers()) {
            if (offer.getSpecialPriceDiff() < 0) anyDiscount = true;
        }
        helper.assertTrue(anyDiscount, "precondition: the reputable owner should have a discount");

        // A second, reputation-less player opens the same villager. Ops bypass the ownership
        // gate on onPickerSubmit, so promote the mock player the same way OwnershipGateGameTest
        // does — through the real op list.
        ServerPlayer stranger = helper.makeMockServerPlayerInLevel();
        level.getServer().getPlayerList().op(stranger.nameAndId(),
                Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
        ProfileController.onPickerSubmit(stranger, villagerId, 1, picks);

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

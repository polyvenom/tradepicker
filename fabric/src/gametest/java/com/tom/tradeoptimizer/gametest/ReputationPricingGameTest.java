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
 * Regression net for reputation pricing (the "reputation doesn't work" CurseForge report).
 *
 * The mod opens the merchant manually instead of vanilla's startTrading, so vanilla's
 * discount pass — Villager.updateSpecialPrices(player), which writes
 * -floor(reputation × priceMultiplier) into each offer's specialPriceDiff — is re-invoked
 * by ProfileController.openMerchant on every open. Two failure modes are pinned here:
 *
 *   1. WIPE-ON-REOPEN (the shipped bug): the vanilla client sends two interact packets
 *      per right-click, so the open path runs twice per click. The second run's stale-menu
 *      teardown fires villager.setTradingPlayer(null) → resetSpecialPrices(), and a
 *      discount refresh done BEFORE that teardown is destroyed — the menu the player sees
 *      shows full prices. Fixed by refreshing INSIDE openMerchant, after the teardown.
 *      Re-opening here deliberately does NOT close the previous menu (mock players never
 *      send close packets), which reproduces the double-fire's stale-menu state exactly.
 *
 *   2. STACKING: updateSpecialPrices accumulates, so if the reset-before-apply inside
 *      refreshSpecialPrices is ever dropped, re-opens compound the discount.
 *
 * Farmer is used for its flat, biome-independent trades (unaffected by the gametest
 * server's experimental Trade Rebalance datapack).
 */
public class ReputationPricingGameTest {

    @GameTest
    public void reputationDiscountSurvivesReopenAndDoesNotStack(GameTestHelper helper) {
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

        // Re-open WITHOUT closing the previous menu — the wipe-on-reopen repro. The stale
        // MerchantMenu's teardown resets special prices mid-open; the discount must be
        // written after that, or the offers sent to the client carry diff 0.
        ProfileController.onPickerSubmit(player, villagerId, 1, picks);

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

        // 3) Two more no-close re-opens: the diff must hold steady, not stack — and a
        //    properly closed session must come back discounted too.
        List<Integer> before = diffs(offers);
        ProfileController.onPickerSubmit(player, villagerId, 1, picks);
        ProfileController.onPickerSubmit(player, villagerId, 1, picks);
        List<Integer> after = diffs(villager.getOffers());
        helper.assertTrue(before.equals(after),
                "discount changed across no-close re-opens: " + before + " -> " + after);

        player.closeContainer(); // vanilla resets special prices when trading stops
        ProfileController.onPickerSubmit(player, villagerId, 1, picks);
        helper.assertTrue(before.equals(diffs(villager.getOffers())),
                "discount not restored after a clean close/reopen cycle");

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

package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import com.tom.tradeoptimizer.villager.ProfileController;
import com.tom.tradeoptimizer.villager.VillagerProfile;
import com.tom.tradeoptimizer.villager.VillagerProfileState;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.TradeSet;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Regression net for the playtest bug "picker skipped on level-up / mystery villager with
 * unpicked trades and no profile".
 *
 * Root cause: vanilla's Mob.convertTo DISCARDS the converting entity and spawns a brand-new
 * one — the UUID is never copied (ConversionType.SINGLE copies position/effects/team only;
 * confirmed against the decompiled 26.2 source). Profiles are keyed by villager UUID, so a
 * zombify→cure round trip used to orphan the profile: the cured villager kept its offers
 * (ZombieVillager.finishConversion copies those) but lost its picks/owner, and the next
 * right-click imported its own picked trades — plus any random vanilla level-up rolls — as
 * permanent "legacy" offers, never showing the picker again.
 *
 * The fix re-keys the profile on every conversion (Fabric MOB_CONVERSION / NeoForge
 * LivingConversionEvent.Post → ProfileController.onMobConverted), so this test drives the
 * exact vanilla conversion entry point (convertTo) rather than simulating it.
 */
public class ConversionProfileGameTest {

    @GameTest
    public void profileFollowsZombifyCureRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        UUID originalId = villager.getUUID();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();

        // Lock in level-1 picks through the real entry point (creates the owned profile).
        List<TradeKey> picks = firstTwoPicks(level, villager, helper);
        ProfileController.onPickerSubmit(player, originalId, 1, picks);

        VillagerProfileState state = VillagerProfileState.get(level);
        helper.assertTrue(state.get(originalId) != null, "profile should exist after submit");

        // Zombify — the same convertTo call vanilla's Zombie.convertVillagerToZombieVillager
        // makes. Produces a NEW entity with a NEW UUID.
        ZombieVillager zombie = villager.convertTo(EntityType.ZOMBIE_VILLAGER,
                ConversionParams.single(villager, true, true), zv -> {});
        helper.assertTrue(zombie != null, "zombify conversion should produce a zombie villager");
        helper.assertTrue(!zombie.getUUID().equals(originalId),
                "vanilla contract: conversion must mint a new UUID — if this ever fails, "
                        + "the re-key hook is redundant");
        helper.assertTrue(state.get(originalId) == null,
                "profile must leave the discarded villager's UUID");
        helper.assertTrue(state.get(zombie.getUUID()) != null,
                "profile must follow onto the zombie villager (parked while zombified)");

        // Cure — the same convertTo call ZombieVillager.finishConversion makes. Again a
        // brand-new Villager with a brand-new UUID.
        Villager cured = zombie.convertTo(EntityType.VILLAGER,
                ConversionParams.single(zombie, true, true), v -> {});
        helper.assertTrue(cured != null, "cure conversion should produce a villager");

        VillagerProfile restored = state.get(cured.getUUID());
        helper.assertTrue(restored != null,
                "profile must follow onto the cured villager — an orphaned profile means the "
                        + "next right-click imports the picked trades as legacy and skips the picker");
        helper.assertTrue(restored.owner().isPresent()
                        && restored.owner().get().equals(player.getUUID()),
                "owner must survive the zombify/cure round trip");
        helper.assertTrue(restored.isFilled(1),
                "level-1 picks must survive the zombify/cure round trip");

        helper.succeed();
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

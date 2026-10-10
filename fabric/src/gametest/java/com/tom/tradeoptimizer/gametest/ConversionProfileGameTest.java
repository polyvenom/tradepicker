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
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.TradeSet;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Guards the profile re-key across conversion. Vanilla's Mob.convertTo discards the converting
 * entity and spawns a new one without copying the UUID, and profiles are keyed by villager UUID.
 * The cured villager keeps its offers (ZombieVillager.finishConversion copies those), so without
 * the re-key (Fabric MOB_CONVERSION / NeoForge LivingConversionEvent.Post ->
 * ProfileController.onMobConverted) the next right-click would import them as permanent legacy
 * offers and never show the picker.
 *
 * Drives vanilla's real zombify and cure methods rather than simulating them.
 */
public class ConversionProfileGameTest {

    @GameTest
    public void profileFollowsZombifyCureRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        UUID originalId = villager.getUUID();
        ServerPlayer player = TestPlayers.near(helper);

        // Lock in level-1 picks (creates the owned profile).
        List<TradeKey> picks = firstTwoPicks(level, villager, helper);
        ProfileController.onPickerSubmit(player, originalId, 1, picks);

        VillagerProfileState state = VillagerProfileState.get(level);
        helper.assertTrue(state.get(originalId) != null, "profile should exist after submit");

        // Zombify through vanilla's entry point (what a zombie killing the villager calls);
        // NeoForge posts LivingConversionEvent from the real callers, not from Mob.convertTo.
        Zombie attacker = helper.spawnWithNoFreeWill(EntityTypes.ZOMBIE, new BlockPos(1, 2, 3));
        helper.assertTrue(attacker.convertVillagerToZombieVillager(level, villager),
                "zombify conversion should succeed");
        // Searched around the villager: NeoForge runs these tests on a 1-block structure, so
        // helper.findOneEntity would look in the wrong place.
        ZombieVillager zombie = level.getEntitiesOfClass(ZombieVillager.class,
                villager.getBoundingBox().inflate(1)).stream().findFirst().orElse(null);
        helper.assertTrue(zombie != null, "zombify conversion should produce a zombie villager");
        helper.assertTrue(!zombie.getUUID().equals(originalId),
                "vanilla contract: conversion must mint a new UUID — if this ever fails, "
                        + "the re-key hook is redundant");
        helper.assertTrue(state.get(originalId) == null,
                "profile must leave the discarded villager's UUID");
        helper.assertTrue(state.get(zombie.getUUID()) != null,
                "profile must follow onto the zombie villager (parked while zombified)");

        // Cure through ZombieVillager.finishConversion (what the cure timer runs); private, so reflection.
        try {
            Method finish = ZombieVillager.class.getDeclaredMethod("finishConversion", ServerLevel.class);
            finish.setAccessible(true);
            finish.invoke(zombie, level);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not run ZombieVillager.finishConversion", e);
        }
        Villager cured = level.getEntitiesOfClass(Villager.class,
                zombie.getBoundingBox().inflate(1)).stream().findFirst().orElse(null);
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
        Villager villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(1, 2, 1));
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

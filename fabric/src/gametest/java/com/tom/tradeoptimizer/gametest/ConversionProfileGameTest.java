package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import com.tom.tradeoptimizer.villager.ProfileController;
import com.tom.tradeoptimizer.villager.VillagerProfile;
import com.tom.tradeoptimizer.villager.VillagerProfileState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Regression net for the playtest bug "picker skipped on level-up / mystery villager with
 * unpicked trades and no profile".
 *
 * Root cause: vanilla's Mob.convertTo DISCARDS the converting entity and spawns a brand-new
 * one — the UUID is never copied (ConversionType.SINGLE copies position/effects/team only;
 * confirmed against the decompiled source). Profiles are keyed by villager UUID, so a
 * zombify→cure round trip used to orphan the profile: the cured villager kept its offers
 * (ZombieVillager.finishConversion copies those) but lost its picks/owner, and the next
 * right-click imported its own picked trades — plus any random vanilla level-up rolls — as
 * permanent "legacy" offers, never showing the picker again.
 *
 * The fix re-keys the profile on every conversion (Fabric MOB_CONVERSION / NeoForge
 * LivingConversionEvent.Post → ProfileController.onMobConverted), so this test drives vanilla's
 * real zombify and cure methods rather than simulating them.
 */
public class ConversionProfileGameTest {

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void profileFollowsZombifyCureRoundTrip(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        UUID originalId = villager.getUUID();
        ServerPlayer player = MockPlayers.near(helper);

        // Lock in level-1 picks through the real entry point (creates the owned profile).
        List<TradeKey> picks = firstTwoPicks(level, villager, helper);
        ProfileController.onPickerSubmit(player, originalId, 1, picks);

        VillagerProfileState state = VillagerProfileState.get(level);
        check(helper, state.get(originalId) != null, "profile should exist after submit");

        // Zombify through vanilla's own entry point: a zombie killing the villager. The real
        // callers matter: NeoForge posts LivingConversionEvent from them, not from Mob.convertTo.
        // Produces a NEW entity with a NEW UUID. HARD so the kill always converts (NORMAL rolls).
        Zombie attacker = helper.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 2, 3));
        Difficulty before = level.getDifficulty();
        level.getServer().setDifficulty(Difficulty.HARD, true);
        try {
            attacker.killedEntity(level, villager);
        } finally {
            level.getServer().setDifficulty(before, true);
        }
        // Searched around the villager, not the test structure: NeoForge runs these tests on a
        // 1-block structure, so helper.findOneEntity would look in the wrong place.
        ZombieVillager zombie = level.getEntitiesOfClass(ZombieVillager.class,
                villager.getBoundingBox().inflate(1)).stream().findFirst().orElse(null);
        check(helper, zombie != null, "zombify conversion should produce a zombie villager");
        check(helper, !zombie.getUUID().equals(originalId),
                "vanilla contract: conversion must mint a new UUID — if this ever fails, "
                        + "the re-key hook is redundant");
        check(helper, state.get(originalId) == null,
                "profile must leave the discarded villager's UUID");
        check(helper, state.get(zombie.getUUID()) != null,
                "profile must follow onto the zombie villager (parked while zombified)");

        // Cure through ZombieVillager.finishConversion, what the cure timer runs when it ends.
        // It's private, hence reflection. Again a brand-new Villager with a brand-new UUID.
        try {
            Method finish = ZombieVillager.class.getDeclaredMethod("finishConversion", ServerLevel.class);
            finish.setAccessible(true);
            finish.invoke(zombie, level);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not run ZombieVillager.finishConversion", e);
        }
        Villager cured = level.getEntitiesOfClass(Villager.class,
                zombie.getBoundingBox().inflate(1)).stream().findFirst().orElse(null);
        check(helper, cured != null, "cure conversion should produce a villager");

        VillagerProfile restored = state.get(cured.getUUID());
        check(helper, restored != null,
                "profile must follow onto the cured villager — an orphaned profile means the "
                        + "next right-click imports the picked trades as legacy and skips the picker");
        check(helper, restored.owner().isPresent()
                        && restored.owner().get().equals(player.getUUID()),
                "owner must survive the zombify/cure round trip");
        check(helper, restored.isFilled(1),
                "level-1 picks must survive the zombify/cure round trip");

        helper.succeed();
    }

    private static Villager spawnFarmer(GameTestHelper helper) {
        var registries = helper.getLevel().registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .setType(VillagerType.PLAINS)
                .setProfession(VillagerProfession.FARMER)
                .setLevel(1));
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
        helper.assertTrue(condition, message);
    }
}

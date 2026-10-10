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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A zombify-cure round trip must keep the villager's profile. Vanilla's Mob.convertTo discards
 * the entity and never copies the UUID, and profiles are keyed by UUID, so without re-keying
 * (Fabric MOB_CONVERSION -> ProfileController.onMobConverted) the cured villager keeps its
 * offers but loses its picks and owner. This drives the real convertTo entry point rather than
 * simulating it.
 */
public class ConversionProfileGameTest {

    @GameTest(structure = "fabric-gametest-api-v1:empty")
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

        // Zombify: the convertTo call vanilla's Zombie.convertVillagerToZombieVillager makes.
        ZombieVillager zombie = villager.convertTo(EntityType.ZOMBIE_VILLAGER,
                ConversionParams.single(villager, true, true), zv -> {});
        check(helper, zombie != null, "zombify conversion should produce a zombie villager");
        check(helper, !zombie.getUUID().equals(originalId),
                "vanilla contract: conversion must mint a new UUID — if this ever fails, "
                        + "the re-key hook is redundant");
        check(helper, state.get(originalId) == null,
                "profile must leave the discarded villager's UUID");
        check(helper, state.get(zombie.getUUID()) != null,
                "profile must follow onto the zombie villager (parked while zombified)");

        // Cure: the convertTo call ZombieVillager.finishConversion makes.
        Villager cured = zombie.convertTo(EntityType.VILLAGER,
                ConversionParams.single(zombie, true, true), v -> {});
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

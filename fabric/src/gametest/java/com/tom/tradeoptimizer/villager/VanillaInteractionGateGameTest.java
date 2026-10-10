package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.gametest.MockPlayers;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Guards the gate that decides when a right-click is handed back to vanilla instead of opening
 * the picker ({@link ProfileController#leaveToVanilla}, and its item rule
 * {@link ProfileController#isVanillaItemInteraction}).
 *
 * The rule has to match Mob.checkAndHandleImportantInteractions exactly. Too narrow and villagers
 * can't be named; too wide and the click falls through to vanilla's merchant screen, whose random
 * rolls would compete with the picker's trades. The easy-to-miss cases are the unnamed name tag
 * and the wrong-mob spawn egg: vanilla drops straight into the trade screen for both.
 */
public class VanillaInteractionGateGameTest {

    /** A name tag only passes once it's been named — an unnamed one must stay with the picker. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void nameTagPassesOnlyWhenNamed(GameTestHelper helper) {
        Villager villager = spawnFarmer(helper);

        ItemStack blank = new ItemStack(Items.NAME_TAG);
        helper.assertFalse(ProfileController.isVanillaItemInteraction(blank, villager),
                "an unnamed name tag does nothing in vanilla, so it must not reach the trade screen");

        ItemStack named = new ItemStack(Items.NAME_TAG);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Bartholomew"));
        helper.assertTrue(ProfileController.isVanillaItemInteraction(named, villager),
                "a named name tag must pass through so the villager can be named");

        helper.succeed();
    }

    /** A spawn egg passes only when it spawns this mob; a mismatched egg would open vanilla's UI. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void spawnEggPassesOnlyWhenItMatches(GameTestHelper helper) {
        Villager villager = spawnFarmer(helper);

        helper.assertTrue(
                ProfileController.isVanillaItemInteraction(new ItemStack(Items.VILLAGER_SPAWN_EGG), villager),
                "a villager spawn egg breeds in vanilla, so it must pass through");
        helper.assertFalse(
                ProfileController.isVanillaItemInteraction(new ItemStack(Items.ZOMBIE_SPAWN_EGG), villager),
                "a wrong-mob spawn egg does nothing in vanilla and would fall into the trade screen");

        helper.succeed();
    }

    /** Everything else — empty hand included — stays with the picker. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void ordinaryItemsStayWithThePicker(GameTestHelper helper) {
        Villager villager = spawnFarmer(helper);

        helper.assertFalse(ProfileController.isVanillaItemInteraction(ItemStack.EMPTY, villager),
                "an empty hand must open the picker, never vanilla's trade screen");
        helper.assertFalse(ProfileController.isVanillaItemInteraction(new ItemStack(Items.EMERALD), villager),
                "holding an ordinary item must still open the picker");
        helper.assertFalse(ProfileController.isVanillaItemInteraction(new ItemStack(Items.DIAMOND_SWORD), villager),
                "holding a weapon must still open the picker");

        helper.succeed();
    }

    /** An ordinary empty-hand click on a working adult villager is ours: it opens the picker. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void plainClickStaysWithThePicker(GameTestHelper helper) {
        Villager villager = spawnFarmer(helper);
        ServerPlayer player = MockPlayers.near(helper);
        helper.assertFalse(ProfileController.leaveToVanilla(player, villager),
                "an empty-hand click on an awake adult farmer must open the picker");
        helper.succeed();
    }

    /** Vanilla refuses to trade with a baby or a sleeping villager, and so must the picker (issue #10). */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void babiesAndSleepersAreLeftToVanilla(GameTestHelper helper) {
        ServerPlayer player = MockPlayers.near(helper);

        Villager sleeper = spawnFarmer(helper);
        sleeper.setSleepingPos(sleeper.blockPosition());
        helper.assertTrue(sleeper.isSleeping(), "precondition: the villager is asleep");
        helper.assertTrue(ProfileController.leaveToVanilla(player, sleeper),
                "a sleeping villager must be left to vanilla, which refuses the trade");
        sleeper.discard();

        Villager baby = spawnFarmer(helper);
        baby.setBaby(true);
        helper.assertTrue(ProfileController.leaveToVanilla(player, baby),
                "a baby must be left to vanilla, which shakes its head");
        helper.succeed();
    }

    /** Nitwits and the unemployed can't trade, so their clicks go to vanilla. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void nonTradersAreLeftToVanilla(GameTestHelper helper) {
        ServerPlayer player = MockPlayers.near(helper);
        Villager villager = spawnFarmer(helper);
        var registries = helper.getLevel().registryAccess();
        villager.setVillagerData(villager.getVillagerData().withProfession(registries, VillagerProfession.NITWIT));
        helper.assertTrue(ProfileController.leaveToVanilla(player, villager), "a nitwit must be left to vanilla");
        villager.setVillagerData(villager.getVillagerData().withProfession(registries, VillagerProfession.NONE));
        helper.assertTrue(ProfileController.leaveToVanilla(player, villager), "an unemployed villager must be left to vanilla");
        helper.succeed();
    }

    private static Villager spawnFarmer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(1));
        return villager;
    }
}

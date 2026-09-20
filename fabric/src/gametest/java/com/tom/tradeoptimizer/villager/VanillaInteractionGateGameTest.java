package com.tom.tradeoptimizer.villager;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Guards the gate that decides when a right-click is handed back to vanilla instead of opening
 * the picker ({@link ProfileController#isVanillaItemInteraction}).
 *
 * The rule has to match Mob.checkAndHandleImportantInteractions exactly. Too narrow and villagers
 * can't be named (the CurseForge report that prompted this); too wide and the click falls through
 * to vanilla's own merchant screen, which must never be reachable — the picker owns this
 * villager's trades and vanilla's random rolls would compete with them.
 *
 * The two easy-to-miss cases are the unnamed name tag and the wrong-mob spawn egg: vanilla does
 * nothing for either and drops straight into the trade screen, so neither may pass.
 */
public class VanillaInteractionGateGameTest {

    /** A name tag only passes once it's been named — an unnamed one must stay with the picker. */
    @GameTest
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
    @GameTest
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
    @GameTest
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

    // -------------------------------------------------------------------------

    private static Villager spawnFarmer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(1));
        return villager;
    }
}

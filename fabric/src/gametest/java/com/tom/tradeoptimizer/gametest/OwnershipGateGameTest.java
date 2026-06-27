package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
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
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.inventory.MerchantMenu;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class OwnershipGateGameTest {

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void ownerCanPickThenReset(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = MockPlayers.mock(helper);

        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p != null, Component.literal("owner submit should create a profile"));
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                Component.literal("owner submit should claim ownership for the submitting player"));
        helper.assertTrue(p.picksFor(1).size() == 2,
                Component.literal("owner's level-1 picks should be stored (got " + p.picksFor(1).size() + ")"));

        villager.setVillagerData(villager.getVillagerData().withLevel(2));
        ProfileController.onPickerSubmit(owner, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        helper.assertTrue(state.get(villagerId).picksFor(2).size() == 2,
                Component.literal("owner's level-2 picks should be stored"));

        ProfileController.onReset(owner, villagerId);
        helper.assertTrue(villager.getVillagerData().level() == 1, Component.literal("reset should drop the villager to level 1"));
        helper.assertTrue(villager.getVillagerXp() == 0, Component.literal("reset should zero the villager XP"));
        helper.assertTrue(villager.getOffers().isEmpty(), Component.literal("reset should clear the live offers"));
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.picksFor(1).isEmpty() && after.picksFor(2).isEmpty(),
                Component.literal("reset should wipe all picks"));
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID()),
                Component.literal("reset should preserve ownership"));
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void nonOwnerIsRejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = MockPlayers.mock(helper);
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        helper.assertTrue(state.get(villagerId).picksFor(1).size() == 2,
                Component.literal("precondition: owner's level-1 picks stored"));

        villager.setVillagerData(villager.getVillagerData().withLevel(2));

        ServerPlayer intruder = MockPlayers.mock(helper);
        ProfileController.onPickerSubmit(intruder, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p.picksFor(2).isEmpty(), Component.literal("non-owner submit must NOT store level-2 picks"));
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                Component.literal("non-owner submit must not change ownership"));
        helper.assertTrue(p.picksFor(1).size() == 2, Component.literal("owner's existing picks must remain intact"));

        ProfileController.onReset(intruder, villagerId);
        helper.assertTrue(villager.getVillagerData().level() == 2,
                Component.literal("non-owner reset must NOT drop the villager level"));
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID())
                        && after.picksFor(1).size() == 2,
                Component.literal("non-owner reset must leave the profile intact"));
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void opBypassesGates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = MockPlayers.mock(helper);
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));

        villager.setVillagerData(villager.getVillagerData().withLevel(2));

        ServerPlayer op = MockPlayers.mock(helper);
        MockPlayers.op(op);
        helper.assertTrue(op.hasPermissions(2),
                Component.literal("sanity: the ops-list entry should grant the mock player permission level 2+"));

        ProfileController.onPickerSubmit(op, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p.picksFor(2).size() == 2,
                Component.literal("op submit should be accepted despite not owning the villager"));
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                Component.literal("op submit should not steal ownership from the original owner"));

        ProfileController.onReset(op, villagerId);
        helper.assertTrue(villager.getVillagerData().level() == 1, Component.literal("op reset should drop the villager to level 1"));
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.picksFor(1).isEmpty() && after.picksFor(2).isEmpty(),
                Component.literal("op reset should wipe picks"));
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID()),
                Component.literal("op reset should preserve the original owner"));
        helper.succeed();
    }

    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void resetClosesStaleTradeSession(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = MockPlayers.mock(helper);

        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        helper.assertTrue(villager.getTradingPlayer() == owner,
                Component.literal("precondition: villager should be trading with the player after a pick"));
        helper.assertTrue(owner.containerMenu != owner.inventoryMenu,
                Component.literal("precondition: the player should have the merchant container open after a pick"));

        ProfileController.onReset(owner, villagerId);
        helper.assertTrue(villager.getTradingPlayer() == null,
                Component.literal("reset must clear the villager's tradingPlayer (stale-session flash guard)"));
        helper.assertTrue(owner.containerMenu == owner.inventoryMenu,
                Component.literal("reset must close the player's stale merchant container (stale-session flash guard)"));
        helper.succeed();
    }

    /**
     * Reopening the merchant while a previous trade session is still live must NOT flash-close.
     *
     * Reproduces the 1.21.x "can pick but can't trade" bug: open the merchant once, then open it
     * again WITHOUT closing the first session (the second open's openMenu would otherwise close the
     * stale MerchantMenu, whose removed() nulls the villager's tradingPlayer right after we set it,
     * so the new menu fails its next-tick stillValid() and self-closes after one frame). A second
     * onPickerSubmit drives the exact open path with a stale container present; afterward the villager
     * must still be trading with the player.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void reopenWithStaleSessionDoesNotFlash(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = MockPlayers.mock(helper);

        // First open: submit picks -> applies offers + opens the merchant.
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        helper.assertTrue(villager.getTradingPlayer() == owner,
                Component.literal("precondition: villager should be trading with the player after the first pick"));
        helper.assertTrue(owner.containerMenu instanceof MerchantMenu,
                Component.literal("precondition: the player should have a merchant menu open after the first pick"));

        // Second open WITHOUT closing the first session — the stale-container case that used to flash.
        // Direct guard: the bug nulled the villager's tradingPlayer during the reopen, so the new
        // MerchantMenu failed its next-tick stillValid() (tradingPlayer == player). A non-null match
        // here is what keeps the menu alive. (We assert tradingPlayer rather than stillValid() because
        // 1.21.4+ folded a reach/distance check into MerchantMenu.stillValid that a headless mock
        // player can't satisfy.) Proven red without the fix.
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        helper.assertTrue(villager.getTradingPlayer() == owner,
                Component.literal("reopen must leave the villager trading with the player (tradingPlayer was nulled = the flash bug)"));
        helper.assertTrue(owner.containerMenu instanceof MerchantMenu,
                Component.literal("reopen must leave a merchant menu open for the player"));
        helper.succeed();
    }

    /**
     * With vanillaBookLimits enabled, a profession with NO book trades (farmer) is unaffected: the
     * per-level book cap relaxes so the two non-book picks still go through (no softlock). The
     * librarian book-cap path itself isn't headless-testable — the gametest server's experimental
     * Trade Rebalance datapack returns null book previews, so 0 books enumerate — and is validated
     * in the live game instead.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void vanillaBookLimitsDoesNotBlockNonBookPicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);
        ServerPlayer owner = MockPlayers.mock(helper);

        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        boolean original = cfg.vanillaBookLimits();
        cfg.setVanillaBookLimits(true);
        try {
            helper.assertTrue(OfferFactory.countBookTemplates(level, villager, 1) == 0,
                    Component.literal("farmer level 1 should have no book templates"));
            ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
            VillagerProfile p = state.get(villagerId);
            helper.assertTrue(p != null && p.picksFor(1).size() == 2,
                    Component.literal("vanillaBookLimits must not block non-book picks (got "
                            + (p == null ? "no profile" : p.picksFor(1).size()) + ")"));
        } finally {
            cfg.setVanillaBookLimits(original);
        }
        helper.succeed();
    }

    private static Villager spawnFarmer(GameTestHelper helper, int villagerLevel) {
        ServerLevel level = helper.getLevel();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(level.registryAccess(), VillagerType.PLAINS)
                .withProfession(level.registryAccess(), VillagerProfession.FARMER)
                .withLevel(villagerLevel));
        return villager;
    }

    private static List<TradeKey> firstTwoPicks(ServerLevel level, Villager villager,
                                                int merchantLevel, GameTestHelper helper) {
        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, merchantLevel);
        helper.assertTrue(available.size() >= 2,
                Component.literal("farmer level " + merchantLevel + " needs >=2 trade options (got " + available.size() + ")"));
        List<TradeKey> picks = new ArrayList<>();
        picks.add(available.get(0).key());
        picks.add(available.get(1).key());
        return picks;
    }
}
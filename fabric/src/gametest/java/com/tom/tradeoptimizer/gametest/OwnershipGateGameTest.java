package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import com.tom.tradeoptimizer.villager.ProfileController;
import com.tom.tradeoptimizer.villager.VillagerProfile;
import com.tom.tradeoptimizer.villager.VillagerProfileState;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
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

/**
 * Guards the ownership / permission gates on {@link ProfileController#onPickerSubmit} and
 * {@link ProfileController#onReset}. These are loader-agnostic authorization rules the port must
 * preserve, so they get a dedicated safety net before any refactor:
 *
 *   - the player who first claims a villager (the owner) can pick trades and reset it;
 *   - any other non-op player is rejected from both;
 *   - a server op bypasses the owner check on both.
 *
 * Cases are observed through persisted state (VillagerProfileState + the villager's data), never
 * through the system messages the controller sends. Op status is granted with the real server op
 * list — ServerPlayer.permissions() resolves against it live, so no relog is needed.
 *
 * Farmer is used for its flat, biome-independent trades (unaffected by the gametest server's
 * experimental Trade Rebalance datapack).
 */
public class OwnershipGateGameTest {

    /** The owner can pick at multiple levels and then reset; ownership survives the reset. */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void ownerCanPickThenReset(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = MockPlayers.mock(helper);

        // Owner picks level 1 -> accepted, claims ownership.
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p != null, "owner submit should create a profile");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "owner submit should claim ownership for the submitting player");
        helper.assertTrue(p.picksFor(1).size() == 2,
                "owner's level-1 picks should be stored (got " + p.picksFor(1).size() + ")");

        // Owner picks level 2 -> accepted.
        villager.setVillagerData(villager.getVillagerData().setLevel(2));
        ProfileController.onPickerSubmit(owner, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        helper.assertTrue(state.get(villagerId).picksFor(2).size() == 2,
                "owner's level-2 picks should be stored");

        // Owner resets -> accepted: novice again, picks wiped, ownership preserved.
        ProfileController.onReset(owner, villagerId);
        helper.assertTrue(villager.getVillagerData().getLevel() == 1, "reset should drop the villager to level 1");
        helper.assertTrue(villager.getVillagerXp() == 0, "reset should zero the villager XP");
        helper.assertTrue(villager.getOffers().isEmpty(), "reset should clear the live offers");
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.picksFor(1).isEmpty() && after.picksFor(2).isEmpty(),
                "reset should wipe all picks");
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID()),
                "reset should preserve ownership");
        helper.succeed();
    }

    /** A non-owner, non-op player is refused both picking and resetting; the profile is untouched. */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void nonOwnerIsRejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = MockPlayers.mock(helper);
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        helper.assertTrue(state.get(villagerId).picksFor(1).size() == 2,
                "precondition: owner's level-1 picks stored");

        villager.setVillagerData(villager.getVillagerData().setLevel(2));

        // A different player (not owner, not op) tries to pick level 2 -> rejected.
        ServerPlayer intruder = MockPlayers.mock(helper);
        ProfileController.onPickerSubmit(intruder, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p.picksFor(2).isEmpty(), "non-owner submit must NOT store level-2 picks");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "non-owner submit must not change ownership");
        helper.assertTrue(p.picksFor(1).size() == 2, "owner's existing picks must remain intact");

        // The same non-owner tries to reset -> rejected, villager + profile unchanged.
        ProfileController.onReset(intruder, villagerId);
        helper.assertTrue(villager.getVillagerData().getLevel() == 2,
                "non-owner reset must NOT drop the villager level");
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID())
                        && after.picksFor(1).size() == 2,
                "non-owner reset must leave the profile intact");
        helper.succeed();
    }

    /** A server op who is not the owner can both pick and reset; the original owner is preserved. */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void opBypassesGates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = MockPlayers.mock(helper);
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));

        villager.setVillagerData(villager.getVillagerData().setLevel(2));

        // An op who is NOT the owner. Write a level-4 entry into the ops list directly — the
        // gametest server's default operator permission level isn't reliably >= 2, so a bare
        // PlayerList.op() wouldn't deterministically clear the hasPermissions(2) check.
        ServerPlayer op = MockPlayers.mock(helper);
        MockPlayers.op(op);
        helper.assertTrue(op.hasPermissions(2),
                "sanity: the ops-list entry should grant the mock player permission level 2+");

        // Op picks level 2 despite not owning -> accepted (ownership bypassed), owner unchanged.
        ProfileController.onPickerSubmit(op, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p.picksFor(2).size() == 2,
                "op submit should be accepted despite not owning the villager");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "op submit should not steal ownership from the original owner");

        // Op resets despite not owning -> accepted; original owner preserved across the reset.
        ProfileController.onReset(op, villagerId);
        helper.assertTrue(villager.getVillagerData().getLevel() == 1, "op reset should drop the villager to level 1");
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.picksFor(1).isEmpty() && after.picksFor(2).isEmpty(),
                "op reset should wipe picks");
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID()),
                "op reset should preserve the original owner");
        helper.succeed();
    }

    /**
     * Resetting must leave no stale trade session behind: it closes the player's open merchant
     * container and clears the villager's tradingPlayer. Otherwise the next openTradingScreen closes
     * the lingering menu mid-open, which nulls the freshly-set tradingPlayer, so the new menu fails
     * MerchantMenu.stillValid() on the next tick — the "first reopen after reset flashes" bug.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void resetClosesStaleTradeSession(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = MockPlayers.mock(helper);

        // Submitting picks applies offers and opens the merchant (setTradingPlayer + openTradingScreen),
        // so afterward the player has a live trade session — exactly the state a reset fires from.
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        helper.assertTrue(villager.getTradingPlayer() == owner,
                "precondition: villager should be trading with the player after a pick");
        helper.assertTrue(owner.containerMenu != owner.inventoryMenu,
                "precondition: the player should have the merchant container open after a pick");

        ProfileController.onReset(owner, villagerId);
        helper.assertTrue(villager.getTradingPlayer() == null,
                "reset must clear the villager's tradingPlayer (stale-session flash guard)");
        helper.assertTrue(owner.containerMenu == owner.inventoryMenu,
                "reset must close the player's stale merchant container (stale-session flash guard)");
        helper.succeed();
    }

    /**
     * Reopening the merchant while a previous trade session is still live must NOT flash-close.
     *
     * This reproduces the 1.21.x "can pick but can't trade" bug: open the merchant once, then open it
     * again WITHOUT closing the first session (the second open's openMenu would otherwise close the
     * stale MerchantMenu, whose removed() nulls the villager's tradingPlayer right after we set it,
     * so the new menu fails MerchantMenu.stillValid() next tick). A second onPickerSubmit drives the
     * exact open path with a stale container present. After it, the villager must still be trading
     * with the player and the live menu must validate.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void reopenWithStaleSessionDoesNotFlash(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = MockPlayers.mock(helper);

        // First open: submit picks -> applies offers + opens the merchant.
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        helper.assertTrue(villager.getTradingPlayer() == owner,
                "precondition: villager should be trading with the player after the first pick");
        helper.assertTrue(owner.containerMenu instanceof MerchantMenu,
                "precondition: the player should have a merchant menu open after the first pick");

        // Second open WITHOUT closing the first session — the stale-container case that used to
        // flash. With the close-before-open teardown this stays valid; without it tradingPlayer
        // would be nulled by the stale menu's removed() and stillValid() would be false.
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        // Direct guard for the bug: the flash happened because the reopen nulled the villager's
        // tradingPlayer, so the new MerchantMenu failed its next-tick stillValid() (tradingPlayer ==
        // player). A non-null match here is exactly what keeps the menu alive. We assert tradingPlayer
        // rather than calling stillValid() directly because 1.21.4+ folded a reach/distance check into
        // MerchantMenu.stillValid that a headless mock player (positioned away from the villager) can't
        // satisfy — irrelevant to this bug. Proven red without the fix.
        helper.assertTrue(villager.getTradingPlayer() == owner,
                "reopen must leave the villager trading with the player (tradingPlayer was nulled = the flash bug)");
        helper.assertTrue(owner.containerMenu instanceof MerchantMenu,
                "reopen must leave a merchant menu open for the player");
        helper.succeed();
    }

    /**
     * With vanillaBookLimits enabled, a profession with NO book trades (farmer) is unaffected: the
     * per-level book cap relaxes so the two non-book picks still go through (no softlock). The
     * librarian book-cap path itself isn't headless-testable — the gametest server's experimental
     * Trade Rebalance datapack returns null book previews, so 0 books enumerate — and is validated
     * in the live game instead.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
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
                    "farmer level 1 should have no book templates");
            ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
            VillagerProfile p = state.get(villagerId);
            helper.assertTrue(p != null && p.picksFor(1).size() == 2,
                    "vanillaBookLimits must not block non-book picks (got "
                            + (p == null ? "no profile" : p.picksFor(1).size()) + ")");
        } finally {
            cfg.setVanillaBookLimits(original);
        }
        helper.succeed();
    }

    // -------------------------------------------------------------------------

    private static Villager spawnFarmer(GameTestHelper helper, int villagerLevel) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .setType(VillagerType.PLAINS)
                .setProfession(VillagerProfession.FARMER)
                .setLevel(villagerLevel));
        return villager;
    }

    private static List<TradeKey> firstTwoPicks(ServerLevel level, Villager villager,
                                                int merchantLevel, GameTestHelper helper) {
        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, merchantLevel);
        helper.assertTrue(available.size() >= 2,
                "farmer level " + merchantLevel + " needs >=2 trade options (got " + available.size() + ")");
        List<TradeKey> picks = new ArrayList<>();
        picks.add(available.get(0).key());
        picks.add(available.get(1).key());
        return picks;
    }
}

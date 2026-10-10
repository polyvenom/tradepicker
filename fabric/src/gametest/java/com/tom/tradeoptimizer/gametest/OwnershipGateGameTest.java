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
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.TradeSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Guards the ownership / permission gates on {@link ProfileController#onPickerSubmit} and
 * {@link ProfileController#onReset}: the owner can pick and reset, any other non-op is rejected,
 * and a server op bypasses the owner check.
 *
 * Cases are observed through persisted state, not the controller's chat messages. Op status uses
 * the real server op list; ServerPlayer.permissions() resolves against it live.
 *
 * Farmer is used for its flat, biome-independent trades, and because trade_rebalance overrides
 * only armorer and librarian. That pack is always on in game tests (GameTestServer enables every
 * feature flag), so prefer a profession the pack doesn't touch.
 */
public class OwnershipGateGameTest {

    /** The owner can pick at multiple levels and then reset; ownership survives the reset. */
    @GameTest
    public void ownerCanPickThenReset(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = TestPlayers.near(helper);

        // Level 1 pick is accepted and claims ownership.
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p != null, "owner submit should create a profile");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "owner submit should claim ownership for the submitting player");
        helper.assertTrue(p.picksFor(1).size() == 2,
                "owner's level-1 picks should be stored (got " + p.picksFor(1).size() + ")");

        villager.setVillagerData(villager.getVillagerData().withLevel(2));
        ProfileController.onPickerSubmit(owner, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        helper.assertTrue(state.get(villagerId).picksFor(2).size() == 2,
                "owner's level-2 picks should be stored");

        // Reset: novice again, picks wiped, ownership preserved.
        ProfileController.onReset(owner, villagerId);
        helper.assertTrue(villager.getVillagerData().level() == 1, "reset should drop the villager to level 1");
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
    @GameTest
    public void nonOwnerIsRejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = TestPlayers.near(helper);
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        owner.closeContainer(); // the owner walks away, so only the ownership rule can refuse the intruder
        helper.assertTrue(state.get(villagerId).picksFor(1).size() == 2,
                "precondition: owner's level-1 picks stored");

        villager.setVillagerData(villager.getVillagerData().withLevel(2));

        // A non-owner non-op pick is rejected.
        ServerPlayer intruder = TestPlayers.near(helper);
        ProfileController.onPickerSubmit(intruder, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p.picksFor(2).isEmpty(), "non-owner submit must NOT store level-2 picks");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "non-owner submit must not change ownership");
        helper.assertTrue(p.picksFor(1).size() == 2, "owner's existing picks must remain intact");

        // So is their reset.
        ProfileController.onReset(intruder, villagerId);
        helper.assertTrue(villager.getVillagerData().level() == 2,
                "non-owner reset must NOT drop the villager level");
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID())
                        && after.picksFor(1).size() == 2,
                "non-owner reset must leave the profile intact");
        helper.succeed();
    }

    /** A server op who is not the owner can both pick and reset; the original owner is preserved. */
    @GameTest
    public void opBypassesGates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = TestPlayers.near(helper);
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        owner.closeContainer(); // the owner walks away: the villager is free for someone else

        villager.setVillagerData(villager.getVillagerData().withLevel(2));

        // An op who is not the owner. Grant an explicit OWNER-level permission set: the gametest
        // server's default operator permissions sit below GAMEMASTERS.
        ServerPlayer op = TestPlayers.near(helper);
        level.getServer().getPlayerList().op(op.nameAndId(),
                Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
        helper.assertTrue(
                op.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.GAMEMASTERS)),
                "sanity: op() should grant the mock player GAMEMASTERS-level permission");

        // Op pick is accepted; owner unchanged.
        ProfileController.onPickerSubmit(op, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p.picksFor(2).size() == 2,
                "op submit should be accepted despite not owning the villager");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "op submit should not steal ownership from the original owner");

        // Op reset is accepted; original owner preserved.
        ProfileController.onReset(op, villagerId);
        helper.assertTrue(villager.getVillagerData().level() == 1, "op reset should drop the villager to level 1");
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.picksFor(1).isEmpty() && after.picksFor(2).isEmpty(),
                "op reset should wipe picks");
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID()),
                "op reset should preserve the original owner");
        helper.succeed();
    }

    /**
     * Reset must close the player's merchant container and clear the villager's tradingPlayer;
     * otherwise the next open closes the lingering menu mid-open, nulling the new tradingPlayer,
     * and the new menu fails MerchantMenu.stillValid() and closes after a frame.
     */
    @GameTest
    public void resetClosesStaleTradeSession(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = TestPlayers.near(helper);

        // Submitting opens the merchant, leaving a live trade session: the state reset fires from.
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
     * With vanillaBookLimits on, a profession with no book trades (farmer) is unaffected: the cap
     * relaxes so two non-book picks still go through. The librarian path isn't headless-testable:
     * trade_rebalance overrides librarian and always runs here, yielding no book previews.
     */
    @GameTest
    public void vanillaBookLimitsDoesNotBlockNonBookPicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);
        ServerPlayer owner = TestPlayers.near(helper);

        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        boolean original = cfg.vanillaBookLimits();
        cfg.setVanillaBookLimits(true);
        try {
            ResourceKey<TradeSet> key = villager.getVillagerData().profession().value().getTrades(1);
            helper.assertTrue(OfferFactory.countBookTemplates(level, villager, key) == 0,
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
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(villagerLevel));
        return villager;
    }

    private static List<TradeKey> firstTwoPicks(ServerLevel level, Villager villager,
                                                int merchantLevel, GameTestHelper helper) {
        ResourceKey<TradeSet> tradeSetKey =
                villager.getVillagerData().profession().value().getTrades(merchantLevel);
        helper.assertTrue(tradeSetKey != null, "farmer level " + merchantLevel + " should have a trade set");
        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, tradeSetKey);
        helper.assertTrue(available.size() >= 2,
                "farmer level " + merchantLevel + " needs >=2 trade options (got " + available.size() + ")");
        List<TradeKey> picks = new ArrayList<>();
        picks.add(available.get(0).key());
        picks.add(available.get(1).key());
        return picks;
    }
}

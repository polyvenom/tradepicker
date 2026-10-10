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

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void ownerCanPickThenReset(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = MockPlayers.near(helper);

        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p != null, "owner submit should create a profile");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "owner submit should claim ownership for the submitting player");
        helper.assertTrue(p.picksFor(1).size() == 2,
                "owner's level-1 picks should be stored (got " + p.picksFor(1).size() + ")");

        villager.setVillagerData(villager.getVillagerData().setLevel(2));
        ProfileController.onPickerSubmit(owner, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        helper.assertTrue(state.get(villagerId).picksFor(2).size() == 2,
                "owner's level-2 picks should be stored");

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

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void nonOwnerIsRejected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = MockPlayers.near(helper);
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        owner.closeContainer(); // the owner walks away, so only the ownership rule can refuse the intruder
        helper.assertTrue(state.get(villagerId).picksFor(1).size() == 2,
                "precondition: owner's level-1 picks stored");

        villager.setVillagerData(villager.getVillagerData().setLevel(2));

        ServerPlayer intruder = MockPlayers.near(helper);
        ProfileController.onPickerSubmit(intruder, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p.picksFor(2).isEmpty(), "non-owner submit must NOT store level-2 picks");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "non-owner submit must not change ownership");
        helper.assertTrue(p.picksFor(1).size() == 2, "owner's existing picks must remain intact");

        ProfileController.onReset(intruder, villagerId);
        helper.assertTrue(villager.getVillagerData().getLevel() == 2,
                "non-owner reset must NOT drop the villager level");
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID())
                        && after.picksFor(1).size() == 2,
                "non-owner reset must leave the profile intact");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void opBypassesGates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);

        ServerPlayer owner = MockPlayers.near(helper);
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        owner.closeContainer(); // the owner walks away: the villager is free for someone else

        villager.setVillagerData(villager.getVillagerData().setLevel(2));

        ServerPlayer op = MockPlayers.near(helper);
        MockPlayers.op(op);
        helper.assertTrue(op.hasPermissions(2),
                "sanity: the ops-list entry should grant the mock player permission level 2+");

        ProfileController.onPickerSubmit(op, villagerId, 2, firstTwoPicks(level, villager, 2, helper));
        VillagerProfile p = state.get(villagerId);
        helper.assertTrue(p.picksFor(2).size() == 2,
                "op submit should be accepted despite not owning the villager");
        helper.assertTrue(p.owner().isPresent() && p.owner().get().equals(owner.getUUID()),
                "op submit should not steal ownership from the original owner");

        ProfileController.onReset(op, villagerId);
        helper.assertTrue(villager.getVillagerData().getLevel() == 1, "op reset should drop the villager to level 1");
        VillagerProfile after = state.get(villagerId);
        helper.assertTrue(after.picksFor(1).isEmpty() && after.picksFor(2).isEmpty(),
                "op reset should wipe picks");
        helper.assertTrue(after.owner().isPresent() && after.owner().get().equals(owner.getUUID()),
                "op reset should preserve the original owner");
        helper.succeed();
    }

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void resetClosesStaleTradeSession(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = MockPlayers.near(helper);

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
     * Open the merchant once, then again WITHOUT closing the first session. Otherwise the second
     * open's openMenu closes the stale MerchantMenu, whose removed() nulls tradingPlayer right
     * after we set it, so the new menu fails its next-tick stillValid() and closes after one frame.
     * A second onPickerSubmit drives that open path with a stale container present; the villager
     * must still be trading with the player afterward.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void reopenWithStaleSessionDoesNotFlash(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        ServerPlayer owner = MockPlayers.near(helper);

        // First open: submit picks -> applies offers + opens the merchant.
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        helper.assertTrue(villager.getTradingPlayer() == owner,
                "precondition: villager should be trading with the player after the first pick");
        helper.assertTrue(owner.containerMenu instanceof MerchantMenu,
                "precondition: the player should have a merchant menu open after the first pick");

        // Second open WITHOUT closing the first session. Assert tradingPlayer rather than
        // stillValid(): it folds in a reach/distance check a headless mock player can't satisfy.
        ProfileController.onPickerSubmit(owner, villagerId, 1, firstTwoPicks(level, villager, 1, helper));
        helper.assertTrue(villager.getTradingPlayer() == owner,
                "reopen must leave the villager trading with the player (tradingPlayer was nulled = the flash bug)");
        helper.assertTrue(owner.containerMenu instanceof MerchantMenu,
                "reopen must leave a merchant menu open for the player");
        helper.succeed();
    }

    /**
     * With vanillaBookLimits enabled, a profession with no book trades (farmer) is unaffected: the
     * cap relaxes so the two non-book picks go through (no softlock). The librarian path isn't
     * headless-testable: trade_rebalance overrides librarian, and returns null book previews here.
     *
     * trade_rebalance is always on in game tests (GameTestServer enables every feature flag except
     * the redstone and minecart experiments, and nothing we set turns it off). It overrides only
     * armorer and librarian, so use another profession (farmer, fletcher, weaponsmith) for
     * anything that measures a trade pool.
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void vanillaBookLimitsDoesNotBlockNonBookPicks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper, 1);
        UUID villagerId = villager.getUUID();
        VillagerProfileState state = VillagerProfileState.get(level);
        ServerPlayer owner = MockPlayers.near(helper);

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

    private static Villager spawnFarmer(GameTestHelper helper, int villagerLevel) {
        ServerLevel level = helper.getLevel();
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
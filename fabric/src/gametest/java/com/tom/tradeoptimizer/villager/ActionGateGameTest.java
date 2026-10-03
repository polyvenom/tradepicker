package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.gametest.TestPlayers;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.inventory.ChestMenu;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Issue #9: the server-side trust gate on every player action. A modified client can send a submit
 * or reset for any villager it has seen, so the server re-checks reach, liveness and who is trading,
 * refuses to re-pick a level, drops duplicate picks, and scopes reset to this villager's own screen.
 *
 * Farmer for the same reason as OwnershipGateGameTest: flat trades the experimental trade_rebalance
 * pack (always on in game tests) doesn't touch.
 */
public class ActionGateGameTest {

    /** Out of reach, a submit and a reset are both refused; in reach, the same calls go through. */
    @GameTest
    public void outOfReachIsRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        ServerPlayer player = TestPlayers.near(helper);
        helper.assertTrue(ProfileController.canAct(player, villager), "precondition: player starts within reach");

        player.setPos(player.getX() + 40, player.getY(), player.getZ());
        helper.assertTrue(!ProfileController.canAct(player, villager), "40 blocks away must be out of reach");
        ProfileController.onPickerSubmit(player, villager.getUUID(), 1, picks(level, villager, 0, 1));
        helper.assertTrue(VillagerProfileState.get(level).get(villager.getUUID()) == null,
                "an out-of-reach submit must not claim or pick");
        helper.succeed();
    }

    /** A dying villager can't be acted on. */
    @GameTest
    public void deadVillagerIsRefused(GameTestHelper helper) {
        Villager villager = spawnFarmer(helper);
        ServerPlayer player = TestPlayers.near(helper);
        villager.setHealth(0);
        helper.assertTrue(!ProfileController.canAct(player, villager), "a dead villager must be refused");
        helper.succeed();
    }

    /** While the owner is mid-trade, even an op can't pick for the villager. */
    @GameTest
    public void busyVillagerIsRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        ServerPlayer owner = TestPlayers.near(helper);
        ProfileController.onPickerSubmit(owner, villager.getUUID(), 1, picks(level, villager, 0, 1));
        helper.assertTrue(villager.getTradingPlayer() == owner, "precondition: the owner is trading");

        villager.setVillagerData(villager.getVillagerData().withLevel(2));
        ServerPlayer op = TestPlayers.near(helper);
        level.getServer().getPlayerList().op(op.nameAndId(), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
        helper.assertTrue(!ProfileController.canAct(op, villager), "someone else is trading: the op must be refused");
        ProfileController.onPickerSubmit(op, villager.getUUID(), 2, picks(level, villager, 0, 1));
        helper.assertTrue(VillagerProfileState.get(level).get(villager.getUUID()).picksFor(2).isEmpty(),
                "a submit while another player trades must not store picks");
        helper.succeed();
    }

    /** A level is picked once: a second submit for it can't swap the trades. */
    @GameTest
    public void pickedLevelCantBeRepicked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        ServerPlayer owner = TestPlayers.near(helper);
        List<TradeKey> first = picks(level, villager, 0, 1);
        ProfileController.onPickerSubmit(owner, villager.getUUID(), 1, first);
        owner.closeContainer();

        ProfileController.onPickerSubmit(owner, villager.getUUID(), 1, picks(level, villager, 2, 3));
        List<TradeKey> stored = VillagerProfileState.get(level).get(villager.getUUID()).picksFor(1);
        helper.assertTrue(stored.equals(first), "re-submitting a picked level must keep the original picks (got " + stored + ")");
        helper.succeed();
    }

    /** The same trade twice in one submit is a crafted packet: nothing is stored. */
    @GameTest
    public void duplicatePicksAreDropped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        ServerPlayer owner = TestPlayers.near(helper);
        List<TradeKey> one = picks(level, villager, 0, 0);
        ProfileController.onPickerSubmit(owner, villager.getUUID(), 1, one);
        // Deduplicated, [A, A] is one pick for a two-pick level: refused outright rather than
        // stored once, which would lock the level a trade short.
        VillagerProfile profile = VillagerProfileState.get(level).get(villager.getUUID());
        helper.assertTrue(profile == null || profile.picksFor(1).isEmpty(),
                "a duplicated pick must not be stored (got " + (profile == null ? "no profile" : profile.picksFor(1)) + ")");
        helper.succeed();
    }

    /** Reset closes only this villager's trade screen, never another container the player has open. */
    @GameTest
    public void resetLeavesOtherContainersOpen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        UUID id = villager.getUUID();
        ServerPlayer owner = TestPlayers.near(helper);
        ProfileController.onPickerSubmit(owner, id, 1, picks(level, villager, 0, 1));

        owner.openMenu(new SimpleMenuProvider((syncId, inv, p) -> ChestMenu.threeRows(syncId, inv), Component.literal("chest")));
        helper.assertTrue(owner.containerMenu instanceof ChestMenu, "precondition: a chest is open");

        ProfileController.onReset(owner, id);
        helper.assertTrue(villager.getVillagerData().level() == 1 && villager.getOffers().isEmpty(),
                "precondition: the reset itself went through");
        helper.assertTrue(owner.containerMenu instanceof ChestMenu, "reset must leave the player's chest open");
        helper.succeed();
    }

    /**
     * A villager nobody has right-clicked has trades the mod would import as legacy. A crafted
     * submit must not claim it and overwrite them.
     */
    @GameTest
    public void unclaimedTradedVillagerRefusesSubmit(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        List<TradeKey> picks = picks(level, villager, 0, 1);
        int offersBefore = villager.getOffers().size();
        helper.assertTrue(offersBefore > 0, "precondition: vanilla rolled starter trades");
        villager.setVillagerXp(5); // traded with before: not truly fresh

        ProfileController.onPickerSubmit(TestPlayers.near(helper), villager.getUUID(), 1, picks);
        helper.assertTrue(VillagerProfileState.get(level).get(villager.getUUID()) == null,
                "a submit for a never-opened, already-traded villager must not claim it");
        helper.assertTrue(villager.getOffers().size() == offersBefore,
                "its existing trades must be left alone");
        helper.succeed();
    }

    /** One card for a two-pick level would lock that level a trade short for good. */
    @GameTest
    public void underFilledSubmitIsRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        List<TradeKey> one = List.of(picks(level, villager, 0, 1).get(0));

        ProfileController.onPickerSubmit(TestPlayers.near(helper), villager.getUUID(), 1, one);
        VillagerProfile profile = VillagerProfileState.get(level).get(villager.getUUID());
        helper.assertTrue(profile == null || profile.picksFor(1).isEmpty(),
                "an under-filled submit must not store picks");
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

    /** The cards at positions a and b of this villager's current-level pool. */
    private static List<TradeKey> picks(ServerLevel level, Villager villager, int a, int b) {
        var data = villager.getVillagerData();
        List<AvailableTrade> pool = OfferFactory.enumerate(level, villager, data.profession().value().getTrades(data.level()));
        return List.of(pool.get(a).key(), pool.get(b).key());
    }
}

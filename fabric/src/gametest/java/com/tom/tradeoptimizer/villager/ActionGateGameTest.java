package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.gametest.MockPlayers;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.inventory.ChestMenu;

import java.util.List;
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

    /** Out of reach, a submit is refused; in reach, the same call goes through. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void outOfReachIsRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        ServerPlayer player = MockPlayers.near(helper);
        helper.assertTrue(ProfileController.canAct(player, villager),
                Component.literal("precondition: player starts within reach"));

        player.setPos(player.getX() + 40, player.getY(), player.getZ());
        helper.assertTrue(!ProfileController.canAct(player, villager),
                Component.literal("40 blocks away must be out of reach"));
        ProfileController.onPickerSubmit(player, villager.getUUID(), 1, picks(level, villager, 0, 1));
        helper.assertTrue(VillagerProfileState.get(level).get(villager.getUUID()) == null,
                Component.literal("an out-of-reach submit must not claim or pick"));
        helper.succeed();
    }

    /** A dying villager can't be acted on. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void deadVillagerIsRefused(GameTestHelper helper) {
        Villager villager = spawnFarmer(helper);
        ServerPlayer player = MockPlayers.near(helper);
        villager.setHealth(0);
        helper.assertTrue(!ProfileController.canAct(player, villager),
                Component.literal("a dead villager must be refused"));
        helper.succeed();
    }

    /** While the owner is mid-trade, even an op can't pick for the villager. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void busyVillagerIsRefused(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        ServerPlayer owner = MockPlayers.near(helper);
        ProfileController.onPickerSubmit(owner, villager.getUUID(), 1, picks(level, villager, 0, 1));
        helper.assertTrue(villager.getTradingPlayer() == owner,
                Component.literal("precondition: the owner is trading"));

        villager.setVillagerData(villager.getVillagerData().withLevel(2));
        ServerPlayer op = MockPlayers.near(helper);
        MockPlayers.op(op);
        helper.assertTrue(!ProfileController.canAct(op, villager),
                Component.literal("someone else is trading: the op must be refused"));
        ProfileController.onPickerSubmit(op, villager.getUUID(), 2, picks(level, villager, 0, 1));
        helper.assertTrue(VillagerProfileState.get(level).get(villager.getUUID()).picksFor(2).isEmpty(),
                Component.literal("a submit while another player trades must not store picks"));
        helper.succeed();
    }

    /** A level is picked once: a second submit for it can't swap the trades. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void pickedLevelCantBeRepicked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        ServerPlayer owner = MockPlayers.near(helper);
        List<TradeKey> first = picks(level, villager, 0, 1);
        ProfileController.onPickerSubmit(owner, villager.getUUID(), 1, first);
        owner.closeContainer();

        ProfileController.onPickerSubmit(owner, villager.getUUID(), 1, picks(level, villager, 2, 3));
        List<TradeKey> stored = VillagerProfileState.get(level).get(villager.getUUID()).picksFor(1);
        helper.assertTrue(stored.equals(first),
                Component.literal("re-submitting a picked level must keep the original picks (got " + stored + ")"));
        helper.succeed();
    }

    /** The same trade twice in one submit is stored once. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void duplicatePicksAreDropped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        ServerPlayer owner = MockPlayers.near(helper);
        List<TradeKey> one = picks(level, villager, 0, 0);
        ProfileController.onPickerSubmit(owner, villager.getUUID(), 1, one);
        List<TradeKey> stored = VillagerProfileState.get(level).get(villager.getUUID()).picksFor(1);
        helper.assertTrue(stored.size() == 1 && stored.get(0).equals(one.get(0)),
                Component.literal("a duplicated pick must be stored once (got " + stored + ")"));
        helper.succeed();
    }

    /** Reset closes only this villager's trade screen, never another container the player has open. */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void resetLeavesOtherContainersOpen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawnFarmer(helper);
        UUID id = villager.getUUID();
        ServerPlayer owner = MockPlayers.near(helper);
        ProfileController.onPickerSubmit(owner, id, 1, picks(level, villager, 0, 1));

        owner.openMenu(new SimpleMenuProvider((syncId, inv, p) -> ChestMenu.threeRows(syncId, inv), Component.literal("chest")));
        helper.assertTrue(owner.containerMenu instanceof ChestMenu,
                Component.literal("precondition: a chest is open"));

        ProfileController.onReset(owner, id);
        helper.assertTrue(villager.getVillagerData().level() == 1 && villager.getOffers().isEmpty(),
                Component.literal("precondition: the reset itself went through"));
        helper.assertTrue(owner.containerMenu instanceof ChestMenu,
                Component.literal("reset must leave the player's chest open"));
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

    /** The cards at positions a and b of this villager's current-level pool. */
    private static List<TradeKey> picks(ServerLevel level, Villager villager, int a, int b) {
        List<AvailableTrade> pool = OfferFactory.enumerate(level, villager, villager.getVillagerData().level());
        return List.of(pool.get(a).key(), pool.get(b).key());
    }
}

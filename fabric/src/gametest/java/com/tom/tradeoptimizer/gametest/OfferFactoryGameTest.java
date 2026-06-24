package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;

import java.util.List;

/**
 * Game tests that run inside a real (headless) Minecraft server, so results reflect
 * ACTUAL runtime behaviour -- unlike a fabric-loader-junit unit test, this environment
 * cannot diverge from how the live game validates and builds data.
 *
 * Registered via the {@code fabric-gametest} entrypoint in this source set's
 * fabric.mod.json. Run with: {@code ./gradlew runGametest}
 *
 * NOTE (book enumeration is not covered here): librarian book trades are biome-specific
 * and return null previews for a synthetic villager in the headless test server.
 */
public class OfferFactoryGameTest {

    /**
     * Core enumeration smoke test: a farmer's level-1 pool must turn into a non-empty
     * list of AvailableTrades, each with a valid (non-empty) preview offer. Farmer
     * trades are flat -- no enchantment or biome gating -- so this reliably exercises the
     * trade-set -> picker-card pipeline against real Minecraft data.
     */
    @GameTest(structure = "fabric-gametest-api-v1:empty")
    public void farmerLevelOneEnumeratesTrades(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        // 1.21.5: setType/setProfession/setLevel -> withType/withProfession/withLevel;
        // VillagerType.PLAINS and VillagerProfession.FARMER are now ResourceKey constants.
        villager.setVillagerData(villager.getVillagerData()
                .withType(level.registryAccess(), VillagerType.PLAINS)
                .withProfession(level.registryAccess(), VillagerProfession.FARMER)
                .withLevel(1));

        List<AvailableTrade> trades = OfferFactory.enumerate(level, villager, 1);
        // 1.21.5: GameTestHelper.assertTrue(boolean, Component) -- string args wrapped with Component.literal.
        helper.assertTrue(!trades.isEmpty(), Component.literal("farmer level 1 enumeration returned no trades"));
        for (AvailableTrade t : trades) {
            helper.assertTrue(!t.previewOffer().getResult().isEmpty(),
                    Component.literal("trade " + t.key().id() + " produced an empty preview result"));
        }
        helper.succeed();
    }
}

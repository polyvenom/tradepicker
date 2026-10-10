package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import net.minecraft.gametest.framework.GameTest;
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
 * Game tests run inside a real headless Minecraft server, so results match live behaviour.
 * Registered via the {@code fabric-gametest} entrypoint in this source set's fabric.mod.json;
 * run with {@code ./gradlew runGametest}.
 *
 * Book enumeration is not covered here: librarian book trades are biome-specific and return null
 * previews for a synthetic villager in the headless test server.
 */
public class OfferFactoryGameTest {

    /**
     * Enumeration smoke test: a farmer's level-1 pool must yield a non-empty list of
     * AvailableTrades, each with a non-empty preview offer. Farmer trades are flat (no enchantment
     * or biome gating).
     */
    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void farmerLevelOneEnumeratesTrades(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .setType(VillagerType.PLAINS)
                .setProfession(VillagerProfession.FARMER)
                .setLevel(1));

        List<AvailableTrade> trades = OfferFactory.enumerate(level, villager, 1);
        helper.assertTrue(!trades.isEmpty(), "farmer level 1 enumeration returned no trades");
        for (AvailableTrade t : trades) {
            helper.assertTrue(!t.previewOffer().getResult().isEmpty(),
                    "trade " + t.key().id() + " produced an empty preview result");
        }
        helper.succeed();
    }
}

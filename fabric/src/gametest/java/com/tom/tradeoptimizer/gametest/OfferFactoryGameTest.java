package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.TradeSet;

import java.util.List;

/**
 * Game tests run inside a real headless Minecraft server, registered via the
 * {@code fabric-gametest} entrypoint in this source set's fabric.mod.json.
 *
 * Book enumeration isn't covered: the librarian's biome-specific book trades return a null
 * preview for a synthetically spawned villager, so they don't expand here even with VillagerType
 * set. It would need a villager placed in a world whose biome activates a variant.
 */
public class OfferFactoryGameTest {

    /**
     * Enumeration smoke test: a farmer's level-1 pool must yield non-empty AvailableTrades with
     * non-empty previews. Farmer trades are flat (no enchantment or biome gating).
     */
    @GameTest
    public void farmerLevelOneEnumeratesTrades(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();

        Villager villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(1));

        ResourceKey<TradeSet> tradeSetKey = villager.getVillagerData().profession().value().getTrades(1);
        helper.assertTrue(tradeSetKey != null, "farmer level 1 should have a trade set");

        List<AvailableTrade> trades = OfferFactory.enumerate(level, villager, tradeSetKey);
        helper.assertTrue(!trades.isEmpty(), "farmer level 1 enumeration returned no trades");
        for (AvailableTrade t : trades) {
            helper.assertTrue(!t.previewOffer().getResult().isEmpty(),
                    "trade " + t.key().id() + " produced an empty preview result");
        }
        helper.succeed();
    }
}

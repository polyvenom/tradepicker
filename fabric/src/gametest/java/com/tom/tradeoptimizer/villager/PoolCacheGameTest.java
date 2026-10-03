package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.trade.AvailableTrade;
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
 * The per-villager trade-pool cache (#20): a picker click and its submit share one enumeration,
 * but a different level or a config change must enumerate afresh — a stale pool would validate a
 * submit against cards the picker never showed.
 */
public class PoolCacheGameTest {

    @GameTest
    public void poolIsReusedUntilLevelOrConfigChanges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.WEAPONSMITH)
                .withLevel(1));
        VillagerProfession prof = villager.getVillagerData().profession().value();
        ResourceKey<TradeSet> novice = prof.getTrades(1);
        ResourceKey<TradeSet> apprentice = prof.getTrades(2);

        List<AvailableTrade> first = ProfileController.enumeratePool(level, villager, novice);
        helper.assertTrue(!first.isEmpty(), "weaponsmith novice should enumerate trades");
        helper.assertTrue(ProfileController.enumeratePool(level, villager, novice) == first,
                "a second enumeration of the same pool should come from the cache");

        List<AvailableTrade> other = ProfileController.enumeratePool(level, villager, apprentice);
        helper.assertTrue(other != first, "another level must not reuse the novice pool");

        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        boolean original = cfg.vanillaPricing();
        cfg.setVanillaPricing(!original);
        try {
            helper.assertTrue(ProfileController.enumeratePool(level, villager, apprentice) != other,
                    "a config change must enumerate afresh");
        } finally {
            cfg.setVanillaPricing(original);
        }
        helper.succeed();
    }
}

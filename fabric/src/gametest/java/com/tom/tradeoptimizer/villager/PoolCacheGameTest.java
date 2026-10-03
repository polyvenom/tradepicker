package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;

import java.util.List;

/**
 * The per-villager trade-pool cache (#20): a picker click and its submit share one enumeration,
 * but a different level, profession or config change must enumerate afresh - a stale pool would
 * validate a submit against cards the picker never showed.
 */
public class PoolCacheGameTest {

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void poolIsReusedUntilLevelProfessionOrConfigChanges(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .setType(VillagerType.PLAINS)
                .setProfession(VillagerProfession.WEAPONSMITH)
                .setLevel(1));

        List<AvailableTrade> first = ProfileController.enumeratePool(level, villager, 1);
        helper.assertTrue(!first.isEmpty(), "weaponsmith novice should enumerate trades");
        helper.assertTrue(ProfileController.enumeratePool(level, villager, 1) == first,
                "a second enumeration of the same pool should come from the cache");

        List<AvailableTrade> other = ProfileController.enumeratePool(level, villager, 2);
        helper.assertTrue(other != first, "another level must not reuse the novice pool");

        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        boolean original = cfg.vanillaPricing();
        cfg.setVanillaPricing(!original);
        try {
            helper.assertTrue(ProfileController.enumeratePool(level, villager, 2) != other,
                    "a config change must enumerate afresh");
        } finally {
            cfg.setVanillaPricing(original);
        }

        List<AvailableTrade> weapons = ProfileController.enumeratePool(level, villager, 2);
        villager.setVillagerData(villager.getVillagerData().setProfession(VillagerProfession.FLETCHER));
        helper.assertTrue(ProfileController.enumeratePool(level, villager, 2) != weapons,
                "a profession change must not reuse the previous profession's pool");
        helper.succeed();
    }
}

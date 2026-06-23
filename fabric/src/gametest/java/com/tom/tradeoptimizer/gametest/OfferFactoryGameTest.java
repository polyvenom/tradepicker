package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.TradeSet;

import java.util.List;

/**
 * Game tests that run inside a real (headless) Minecraft server, so results reflect
 * ACTUAL runtime behaviour — unlike a fabric-loader-junit unit test, this environment
 * cannot diverge from how the live game validates and builds data.
 *
 * Registered via the {@code fabric-gametest} entrypoint in this source set's
 * fabric.mod.json. Run with: {@code ./gradlew runGametest}
 *
 * NOTE (book enumeration is intentionally not covered here yet): in vanilla 26.1.2 the
 * librarian book trades are biome-specific variants (emerald_and_book_<biome>_enchanted_book
 * ×7) that return a null preview offer for a synthetically-spawned villager, so they don't
 * expand in this headless world even with VillagerType set. The live modpack instead exposes
 * a single non-biome emerald_and_book_enchanted_book trade (per the server log), which is why
 * books work in-game. Reproducing book expansion in a gametest needs the villager placed in a
 * world whose biome actually activates a variant — tracked as a follow-up.
 */
public class OfferFactoryGameTest {

    /**
     * Core enumeration smoke test: a farmer's level-1 pool must turn into a non-empty
     * list of AvailableTrades, each with a valid (non-empty) preview offer. Farmer
     * trades are flat — no enchantment or biome gating — so this reliably exercises the
     * trade-set -> picker-card pipeline against real Minecraft data.
     */
    @GameTest
    public void farmerLevelOneEnumeratesTrades(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();

        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
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

package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.TradeSet;

import java.util.List;
import java.util.Optional;

/**
 * Guards the deterministic price seed behind the "no reopen-to-reroll" promise. With vanilla
 * pricing on, each (villager, TradeKey) draws its price from RandomSource.create(priceSeed(...));
 * an unstable seed would let the preview differ from the applied price and let a player reopen
 * the picker to re-roll. Asserts, with that mode forced on, that:
 *   - generate() yields an identical price across repeated calls;
 *   - the applied price matches the picker's preview price; and
 *   - re-enumerating (a reopen) reproduces the same preview prices.
 *
 * The default is min-price mode, so the test flips vanillaPricing on and restores it (each
 * gametest body runs atomically on the server thread). Farmer trades are flat, untouched by
 * trade_rebalance (see OwnershipGateGameTest), and several have a randomized cost range, so the
 * seed path is exercised.
 */
public class PriceSeedGameTest {

    @GameTest
    public void seededPriceIsStableAndMatchesPreview(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();

        Villager villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(1));

        ResourceKey<TradeSet> tradeSetKey =
                villager.getVillagerData().profession().value().getTrades(1);
        helper.assertTrue(tradeSetKey != null, "farmer level 1 should have a trade set");

        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        boolean originalMode = cfg.vanillaPricing();
        cfg.setVanillaPricing(true);
        try {
            List<AvailableTrade> first = OfferFactory.enumerate(level, villager, tradeSetKey);
            helper.assertTrue(!first.isEmpty(), "farmer level 1 should enumerate at least one trade");

            for (AvailableTrade t : first) {
                TradeKey k = t.key();
                int previewPrice = t.previewOffer().getBaseCostA().getCount();

                Optional<MerchantOffer> o1 = OfferFactory.generate(level, villager, k, 1);
                Optional<MerchantOffer> o2 = OfferFactory.generate(level, villager, k, 1);
                helper.assertTrue(o1.isPresent() && o2.isPresent(),
                        "generate should produce an offer for " + k.id());

                int p1 = o1.get().getBaseCostA().getCount();
                int p2 = o2.get().getBaseCostA().getCount();
                helper.assertTrue(p1 == p2,
                        k.id() + ": seeded price not stable across generate() calls (" + p1 + " vs " + p2 + ")");
                helper.assertTrue(p1 == previewPrice,
                        k.id() + ": applied price " + p1 + " != preview price " + previewPrice
                                + " (this is the reopen-to-reroll guarantee)");
            }

            // Re-enumerating simulates reopening the picker: every preview price must be identical.
            List<AvailableTrade> second = OfferFactory.enumerate(level, villager, tradeSetKey);
            helper.assertTrue(second.size() == first.size(),
                    "re-enumeration changed the trade count (" + first.size() + " -> " + second.size() + ")");
            for (int i = 0; i < first.size(); i++) {
                int a = first.get(i).previewOffer().getBaseCostA().getCount();
                int b = second.get(i).previewOffer().getBaseCostA().getCount();
                helper.assertTrue(a == b,
                        first.get(i).key().id() + ": preview price changed on reopen (" + a + " -> " + b + ")");
            }
        } finally {
            cfg.setVanillaPricing(originalMode);
        }

        helper.succeed();
    }
}

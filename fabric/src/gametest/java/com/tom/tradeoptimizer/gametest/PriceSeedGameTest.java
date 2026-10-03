package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.List;
import java.util.Optional;

public class PriceSeedGameTest {

    @GameTest(template = "fabric-gametest-api-v1:empty")
    public void seededPriceIsStableAndMatchesPreview(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();

        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .setType(VillagerType.PLAINS)
                .setProfession(VillagerProfession.FARMER)
                .setLevel(1));

        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        boolean originalMode = cfg.vanillaPricing();
        cfg.setVanillaPricing(true);
        try {
            List<AvailableTrade> first = OfferFactory.enumerate(level, villager, 1);
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

            List<AvailableTrade> second = OfferFactory.enumerate(level, villager, 1);
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
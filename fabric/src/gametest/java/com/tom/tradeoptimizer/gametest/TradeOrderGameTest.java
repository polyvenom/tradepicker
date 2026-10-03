package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import com.tom.tradeoptimizer.villager.ProfileController;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.List;

/**
 * Trade order = click order (#23). The picker sends its picks in the order they were clicked; the
 * villager must list the trades in that order, not in the pool's. Picks the pool's second card
 * first, so a server that re-sorted to pool order would fail. Farmer novice trades all pay out
 * emeralds, so the order is read off what each one wants.
 */
public class TradeOrderGameTest {

    @GameTest
    public void offersFollowPickOrder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, VillagerProfession.FARMER)
                .withLevel(1));

        List<AvailableTrade> available = OfferFactory.enumerate(level, villager, 1);
        helper.assertTrue(available.size() >= 2, "farmer level 1 needs >=2 trade options");
        TradeKey first = available.get(1).key();
        TradeKey second = available.get(0).key();

        ProfileController.onPickerSubmit(MockPlayers.near(helper), villager.getUUID(), 1, List.of(first, second));

        MerchantOffers offers = villager.getOffers();
        helper.assertTrue(offers.size() == 2, "expected the two picked trades, got " + offers.size());
        helper.assertTrue(offers.get(0).getBaseCostA().getItem() == available.get(1).previewOffer().getBaseCostA().getItem(),
                "the first-clicked trade should be listed first");
        helper.assertTrue(offers.get(1).getBaseCostA().getItem() == available.get(0).previewOffer().getBaseCostA().getItem(),
                "the second-clicked trade should be listed second");
        helper.succeed();
    }
}

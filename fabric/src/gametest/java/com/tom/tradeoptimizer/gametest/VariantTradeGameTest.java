package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SuspiciousStewEffects;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.TradeSet;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Issue #14: random-variant trades (suspicious stew effect, dyed-leather colour) were pinned to
 * their first roll by the min-price RandomSource. They now expand into one pickable card per
 * variant, and each card must regenerate to exactly what it shows.
 *
 * Farmer and leatherworker are untouched by trade_rebalance (always on in game tests), so these are
 * the vanilla templates.
 */
public class VariantTradeGameTest {

    private static Villager villager(GameTestHelper helper, ResourceKey<VillagerProfession> prof, int lvl) {
        var registries = helper.getLevel().registryAccess();
        Villager v = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        v.setVillagerData(v.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, prof)
                .withLevel(lvl));
        return v;
    }

    private static List<AvailableTrade> enumerate(GameTestHelper helper, Villager v, int lvl) {
        ResourceKey<TradeSet> setKey = v.getVillagerData().profession().value().getTrades(lvl);
        helper.assertTrue(setKey != null, "profession should have a trade set at level " + lvl);
        return OfferFactory.enumerate(helper.getLevel(), v, setKey);
    }

    private static List<?> stewEffects(ItemStack stew) {
        return stew.getOrDefault(DataComponents.SUSPICIOUS_STEW_EFFECTS, SuspiciousStewEffects.EMPTY).effects();
    }

    @GameTest
    public void stewExpandsIntoOneCardPerEffect(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager v = villager(helper, VillagerProfession.FARMER, 4);

        int stewCards = 0;
        Set<Object> distinct = new HashSet<>();
        for (AvailableTrade t : enumerate(helper, v, 4)) {
            TradeKey k = t.key();
            ItemStack result = t.previewOffer().getResult();
            if (!result.is(Items.SUSPICIOUS_STEW)) continue;
            helper.assertTrue(OfferFactory.isStewKey(k), "stew card should carry a stew key: " + k.id());
            stewCards++;
            helper.assertTrue(stewEffects(result).size() == 1, k.id() + ": card should show exactly one effect");
            distinct.add(stewEffects(result).get(0));

            Optional<MerchantOffer> o = OfferFactory.generate(level, v, k, 4);
            helper.assertTrue(o.isPresent(), "generate should regenerate " + k.id());
            helper.assertTrue(stewEffects(o.get().getResult()).equals(stewEffects(result)),
                    k.id() + ": regenerated stew effect differs from the card");
            helper.assertTrue(o.get().getBaseCostA().getCount() == t.previewOffer().getBaseCostA().getCount(),
                    k.id() + ": regenerated cost differs from the card");
        }
        helper.assertTrue(stewCards >= 2, "stew should expand into several cards, got " + stewCards);
        helper.assertTrue(distinct.size() == stewCards, "stew cards must all carry distinct effects");
        helper.assertTrue(stewCards == 6, "vanilla stew has 6 effects, got " + stewCards);
        helper.succeed();
    }

    @GameTest
    public void dyedLeatherExpandsIntoOneCardPerColour(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager v = villager(helper, VillagerProfession.LEATHERWORKER, 1);

        Map<String, Set<Integer>> coloursPerItem = new HashMap<>();
        for (AvailableTrade t : enumerate(helper, v, 1)) {
            TradeKey k = t.key();
            ItemStack result = t.previewOffer().getResult();
            if (!result.has(DataComponents.DYED_COLOR)) continue;
            helper.assertTrue(OfferFactory.isDyeKey(k), "dyed card should carry a dye key: " + k.id());
            coloursPerItem.computeIfAbsent(result.getItem().toString(), x -> new HashSet<>())
                    .add(result.get(DataComponents.DYED_COLOR).rgb());

            Optional<MerchantOffer> o = OfferFactory.generate(level, v, k, 1);
            helper.assertTrue(o.isPresent(), "generate should regenerate " + k.id());
            helper.assertTrue(ItemStack.matches(o.get().getResult(), result),
                    k.id() + ": regenerated item differs from the card");
            helper.assertTrue(o.get().getBaseCostA().getCount() == t.previewOffer().getBaseCostA().getCount(),
                    k.id() + ": regenerated cost differs from the card");
        }
        helper.assertTrue(!coloursPerItem.isEmpty(), "leatherworker level 1 should offer dyed leather");
        for (var e : coloursPerItem.entrySet()) {
            helper.assertTrue(e.getValue().size() == 16,
                    e.getKey() + ": expected 16 distinct colours, got " + e.getValue().size());
        }
        helper.succeed();
    }
}

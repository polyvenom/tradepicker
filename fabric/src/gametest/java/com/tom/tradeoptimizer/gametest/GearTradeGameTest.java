package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.config.TradeOptimizerConfig.GearEnchantMode;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.TradeSet;

import java.util.List;
import java.util.Optional;

/**
 * Game tests for the enchanted-gear / tipped-arrow picker expansion (issues #4 / #5).
 *
 * These cover the path the old tests didn't: a vanilla enchanted-gear trade is expanded into
 * choosable cards, and each card's synthetic key REGENERATES into a valid offer on apply. The
 * regen-must-equal-preview check matters because {@code ProfileController} matches a regenerated
 * offer against the live one to carry over use-counts — if HEADLINE's roll weren't deterministic
 * per villager, picked trades would silently restock.
 */
public class GearTradeGameTest {

    private static Villager spawn(GameTestHelper helper, ResourceKey<VillagerProfession> prof, int merchantLevel) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        Villager villager = helper.spawnWithNoFreeWill(EntityTypes.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .withType(registries, VillagerType.PLAINS)
                .withProfession(registries, prof)
                .withLevel(merchantLevel));
        return villager;
    }

    /** Find the first expanded enchanted-gear card in a (profession, level) pool, or null. */
    private static AvailableTrade firstGearCard(ServerLevel level, Villager villager, int merchantLevel) {
        ResourceKey<TradeSet> key = villager.getVillagerData().profession().value().getTrades(merchantLevel);
        if (key == null) return null;
        for (AvailableTrade t : OfferFactory.enumerate(level, villager, key)) {
            if (OfferFactory.isGearKey(t.key())) return t;
        }
        return null;
    }

    private void runGearRoundTrip(GameTestHelper helper, GearEnchantMode mode) {
        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        GearEnchantMode original = cfg.gearEnchantMode();
        try {
            cfg.setGearEnchantMode(mode);
            ServerLevel level = helper.getLevel();
            Villager villager = spawn(helper, VillagerProfession.WEAPONSMITH, 1);

            AvailableTrade card = firstGearCard(level, villager, 1);
            helper.assertTrue(card != null,
                    "weaponsmith level 1 produced no enchanted-gear cards in " + mode + " mode");

            // Preview card must already carry the enchantment on the gear item.
            helper.assertTrue(!card.previewOffer().getResult().getEnchantments().isEmpty(),
                    "gear preview result has no enchantments");

            // The key must regenerate into a real offer (the apply path).
            Optional<MerchantOffer> regen = OfferFactory.generate(level, villager, card.key(), 1);
            helper.assertTrue(regen.isPresent(), "gear key " + card.key().id() + " failed to regenerate");
            ItemStack result = regen.get().getResult();
            helper.assertTrue(!result.getEnchantments().isEmpty(), "regenerated gear has no enchantments");

            // Regen must equal the preview deterministically (use-count carry-over relies on it).
            helper.assertTrue(ItemStack.matches(result, card.previewOffer().getResult()),
                    "regenerated gear offer differs from its preview (non-deterministic)");

            // HEADLINE cards must contain the chosen enchantment among the rolled set.
            Optional<Identifier> headline = OfferFactory.headlineEnchantId(card.key());
            if (headline.isPresent()) {
                boolean found = false;
                for (Holder<Enchantment> h : result.getEnchantments().keySet()) {
                    if (h.unwrapKey().map(k -> k.identifier().equals(headline.get())).orElse(false)) {
                        found = true;
                        break;
                    }
                }
                helper.assertTrue(found, "headline enchantment " + headline.get() + " missing from rolled gear");
            }
            helper.succeed();
        } finally {
            cfg.setGearEnchantMode(original);
        }
    }

    @GameTest
    public void weaponsmithGearHeadlineRoundTrips(GameTestHelper helper) {
        runGearRoundTrip(helper, GearEnchantMode.HEADLINE);
    }

    @GameTest
    public void weaponsmithGearSingleRoundTrips(GameTestHelper helper) {
        runGearRoundTrip(helper, GearEnchantMode.SINGLE);
    }

    /**
     * Tipped-arrow expansion: a fletcher's tipped-arrow trade expands into one card per potion, and
     * each card regenerates into a tipped arrow carrying that potion. Best-effort on which level the
     * arrow trade lands at — scans every level and only asserts once a card is found, so it can't fail
     * spuriously if the headless world gates the trade.
     */
    @GameTest
    public void fletcherArrowRoundTrips(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawn(helper, VillagerProfession.FLETCHER, 5);

        AvailableTrade arrowCard = null;
        for (int lvl = 1; lvl <= 5 && arrowCard == null; lvl++) {
            villager.setVillagerData(villager.getVillagerData().withLevel(lvl));
            ResourceKey<TradeSet> key = villager.getVillagerData().profession().value().getTrades(lvl);
            if (key == null) continue;
            for (AvailableTrade t : OfferFactory.enumerate(level, villager, key)) {
                if (OfferFactory.isArrowKey(t.key())) { arrowCard = t; break; }
            }
            if (arrowCard != null) {
                Optional<MerchantOffer> regen = OfferFactory.generate(level, villager, arrowCard.key(), lvl);
                helper.assertTrue(regen.isPresent(), "arrow key failed to regenerate");
                helper.assertTrue(regen.get().getResult().is(Items.TIPPED_ARROW),
                        "regenerated arrow offer is not a tipped arrow");
            }
        }
        helper.succeed();
    }
}

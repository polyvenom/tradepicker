package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.config.TradeOptimizerConfig.GearEnchantMode;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.Optional;

/**
 * Game tests for the enchanted-gear / tipped-arrow picker expansion on MC 1.21.1 (issues #4 / #5).
 *
 * Unlike the librarian book trades (biome-gated → no preview in this headless world), the gear /
 * tipped-arrow listings (EnchantedItemForEmeralds / TippedArrowForItemsAndEmeralds) generate a real
 * offer for a synthetic villager, so they DO exercise here. We scan all five merchant levels for the
 * first gear / arrow listing since the level a profession places it at differs from the 26.x line.
 */
public class GearTradeGameTest {

    private static final String EMPTY = "fabric-gametest-api-v1:empty";

    private static Villager spawn(GameTestHelper helper, VillagerProfession prof, int merchantLevel) {
        Villager villager = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        villager.setVillagerData(villager.getVillagerData()
                .setType(VillagerType.PLAINS)
                .setProfession(prof)
                .setLevel(merchantLevel));
        return villager;
    }

    /** First gear card across all merchant levels, with the level it was found at. */
    private record GearHit(AvailableTrade card, int level) {}

    private static GearHit firstGearCard(ServerLevel level, Villager villager) {
        for (int ml = 1; ml <= 5; ml++) {
            villager.setVillagerData(villager.getVillagerData().setLevel(ml));
            for (AvailableTrade t : OfferFactory.enumerate(level, villager, ml)) {
                if (OfferFactory.isGearKey(t.key())) return new GearHit(t, ml);
            }
        }
        return null;
    }

    private void gearRoundTrip(GameTestHelper helper, GearEnchantMode mode) {
        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        GearEnchantMode original = cfg.gearEnchantMode();
        try {
            cfg.setGearEnchantMode(mode);
            ServerLevel level = helper.getLevel();
            Villager villager = spawn(helper, VillagerProfession.WEAPONSMITH, 1);

            GearHit hit = firstGearCard(level, villager);
            helper.assertTrue(hit != null, "weaponsmith produced no enchanted-gear cards in " + mode + " mode");

            helper.assertTrue(!hit.card().previewOffer().getResult().getEnchantments().isEmpty(),
                    "gear preview result has no enchantments");

            Optional<MerchantOffer> regen = OfferFactory.generate(level, villager, hit.card().key(), hit.level());
            helper.assertTrue(regen.isPresent(), "gear key " + hit.card().key().id() + " failed to regenerate");
            ItemStack result = regen.get().getResult();
            helper.assertTrue(!result.getEnchantments().isEmpty(), "regenerated gear has no enchantments");

            helper.assertTrue(ItemStack.matches(result, hit.card().previewOffer().getResult()),
                    "regenerated gear offer differs from its preview (non-deterministic)");

            Optional<ResourceLocation> headline = OfferFactory.headlineEnchantId(hit.card().key());
            if (headline.isPresent()) {
                boolean found = false;
                for (Holder<Enchantment> h : result.getEnchantments().keySet()) {
                    if (h.unwrapKey().map(k -> k.location().equals(headline.get())).orElse(false)) {
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

    @GameTest(template = EMPTY)
    public void weaponsmithGearHeadlineRoundTrips(GameTestHelper helper) {
        gearRoundTrip(helper, GearEnchantMode.HEADLINE);
    }

    @GameTest(template = EMPTY)
    public void weaponsmithGearSingleRoundTrips(GameTestHelper helper) {
        gearRoundTrip(helper, GearEnchantMode.SINGLE);
    }

    @GameTest(template = EMPTY)
    public void fletcherArrowRoundTrips(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = spawn(helper, VillagerProfession.FLETCHER, 1);
        for (int ml = 1; ml <= 5; ml++) {
            villager.setVillagerData(villager.getVillagerData().setLevel(ml));
            for (AvailableTrade t : OfferFactory.enumerate(level, villager, ml)) {
                if (OfferFactory.isArrowKey(t.key())) {
                    Optional<MerchantOffer> regen = OfferFactory.generate(level, villager, t.key(), ml);
                    helper.assertTrue(regen.isPresent(), "arrow key failed to regenerate");
                    helper.assertTrue(regen.get().getResult().is(Items.TIPPED_ARROW),
                            "regenerated arrow offer is not a tipped arrow");
                    helper.succeed();
                    return;
                }
            }
        }
        helper.succeed();
    }
}

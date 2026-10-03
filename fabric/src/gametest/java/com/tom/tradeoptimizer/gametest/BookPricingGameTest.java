package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Issues #15 and #16: book cards sell what they say, and book prices under vanillaPricing, where
 * each (villager, book) draws a seeded price.
 *
 *  - #16: every card sells exactly the enchantment and level its key names. A book listing rolls
 *    from its own enchantment list (a biome tag under Trade Rebalance), not necessarily
 *    #minecraft:tradeable, so the cards are steered by the list the listing really uses.
 *  - The price a book card shows must be the price the villager then charges. Regeneration used to
 *    share one seeded stream across every listing it tried, so rolls burnt by earlier attempts
 *    shifted the price the real listing read.
 *  - Single-level books (Mending, Silk Touch, curses, the rebalance's fixed-level specials) must
 *    get a real price roll. The steering prefix assumed a level roll that Minecraft skips when
 *    min == max, so the spare slot was eaten by the price roll and pinned it to the minimum.
 *
 * Librarians across every biome and rank. Game tests always run with trade_rebalance on (see
 * OwnershipGateGameTest), whose librarian pools differ by biome; the checks read what each card
 * actually sells, so they hold whatever the pool is.
 */
public class BookPricingGameTest {
    private static final List<ResourceKey<VillagerType>> TYPES = List.of(VillagerType.PLAINS, VillagerType.DESERT,
            VillagerType.SAVANNA, VillagerType.TAIGA, VillagerType.SNOW, VillagerType.JUNGLE, VillagerType.SWAMP);

    @GameTest(maxTicks = 400, structure = "fabric-gametest-api-v1:empty")
    public void bookPriceShownIsPriceCharged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        boolean original = cfg.vanillaPricing();
        cfg.setVanillaPricing(true);
        List<String> problems = new ArrayList<>();
        int books = 0;
        try {
            for (ResourceKey<VillagerType> type : TYPES) {
                Villager v = librarian(helper, type);
                for (int lvl = 1; lvl <= 5; lvl++) {
                    for (AvailableTrade card : OfferFactory.enumerate(level, v, lvl)) {
                        if (!OfferFactory.isBookKey(card.key())) continue;
                        books++;
                        Optional<MerchantOffer> granted = OfferFactory.generate(level, v, card.key(), lvl);
                        if (granted.isEmpty()) {
                            problems.add(card.key().id() + " L" + lvl + ": not regenerated");
                        } else if (!samePrice(card.previewOffer(), granted.get())) {
                            problems.add(card.key().id() + " " + type.identifier().getPath() + " L" + lvl + ": card "
                                    + price(card.previewOffer()) + ", charged " + price(granted.get()));
                        }
                    }
                }
                v.discard();
            }
        } finally {
            cfg.setVanillaPricing(original);
        }
        helper.assertTrue(books > 0, Component.literal("no book cards found on any librarian"));
        report(helper, problems, books + " book cards");
    }

    @GameTest(maxTicks = 400, structure = "fabric-gametest-api-v1:empty")
    public void singleLevelBooksGetARealPriceRoll(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        boolean original = cfg.vanillaPricing();
        cfg.setVanillaPricing(true);
        Map<String, List<Integer>> prices = new HashMap<>(); // single-level book card -> price per villager
        try {
            for (ResourceKey<VillagerType> type : TYPES) {
                for (int n = 0; n < 8; n++) {
                    Villager v = librarian(helper, type);
                    for (int lvl = 1; lvl <= 5; lvl++) {
                        for (AvailableTrade card : OfferFactory.enumerate(level, v, lvl)) {
                            if (OfferFactory.isBookKey(card.key()) && singleLevel(card.previewOffer().getResult())) {
                                prices.computeIfAbsent(type.identifier().getPath() + " " + card.key().id(), k -> new ArrayList<>())
                                        .add(card.previewOffer().getItemCostA().count());
                            }
                        }
                    }
                    v.discard();
                }
            }
        } finally {
            cfg.setVanillaPricing(original);
        }
        helper.assertTrue(!prices.isEmpty(), Component.literal("no single-level enchanted book found in any librarian pool"));
        List<String> problems = new ArrayList<>();
        prices.forEach((card, seen) -> {
            // 8 villagers drawing from vanilla's price range: one price for all of them means pinned.
            if (seen.size() >= 6 && seen.stream().distinct().count() == 1) {
                problems.add(card + ": every villager charges " + seen.get(0));
            }
        });
        report(helper, problems, prices.size() + " single-level book cards");
    }

    /** Issue #16: every book card sells exactly the enchantment and level its key names. */
    @GameTest(maxTicks = 400, structure = "fabric-gametest-api-v1:empty")
    public void bookCardsSellWhatTheySay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<String> problems = new ArrayList<>();
        int books = 0;
        for (ResourceKey<VillagerType> type : TYPES) {
            Villager v = librarian(helper, type);
            for (int lvl = 1; lvl <= 5; lvl++) {
                Map<String, Integer> seen = new HashMap<>();
                for (AvailableTrade card : OfferFactory.enumerate(level, v, lvl)) {
                    if (!OfferFactory.isBookKey(card.key())) continue;
                    books++;
                    String key = card.key().id().toString();
                    if (seen.merge(key, 1, Integer::sum) > 1) problems.add(type.identifier().getPath() + " L" + lvl + ": duplicate card " + key);
                    ItemEnchantments stored = card.previewOffer().getResult()
                            .getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
                    String sold = stored.entrySet().stream()
                            .map(e -> e.getKey().unwrapKey().map(k -> k.identifier().getPath()).orElse("?") + " " + e.getIntValue())
                            .reduce((x, y) -> x + ", " + y).orElse("nothing");
                    // key path: book/<level>/<ns>/<path>
                    String[] parts = card.key().id().getPath().split("/", 4);
                    String expected = parts[3] + " " + parts[1];
                    if (!sold.equals(expected)) problems.add(type.identifier().getPath() + " L" + lvl + ": card " + key + " sells " + sold);
                }
            }
            v.discard();
        }
        helper.assertTrue(books > 0, Component.literal("no book cards found on any librarian"));
        report(helper, problems, books + " book cards");
    }

    private static boolean singleLevel(ItemStack book) {
        ItemEnchantments stored = book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        if (stored.size() != 1) return false;
        Holder<Enchantment> e = stored.keySet().iterator().next();
        return e.value().getMinLevel() == e.value().getMaxLevel();
    }

    private static boolean samePrice(MerchantOffer a, MerchantOffer b) {
        return a.getItemCostA().count() == b.getItemCostA().count()
                && a.getItemCostB().map(c -> c.count()).orElse(0).equals(b.getItemCostB().map(c -> c.count()).orElse(0));
    }

    private static String price(MerchantOffer o) {
        return o.getItemCostA().count() + " + " + o.getItemCostB().map(c -> c.count()).orElse(0);
    }

    private static Villager librarian(GameTestHelper helper, ResourceKey<VillagerType> type) {
        var registries = helper.getLevel().registryAccess();
        Villager v = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        v.setVillagerData(v.getVillagerData().withType(registries, type)
                .withProfession(registries, VillagerProfession.LIBRARIAN).withLevel(1));
        return v;
    }

    private static void report(GameTestHelper helper, List<String> problems, String scope) {
        if (problems.isEmpty()) { helper.succeed(); return; }
        StringBuilder sb = new StringBuilder(problems.size() + " problem(s), " + scope + " checked:");
        for (int i = 0; i < Math.min(12, problems.size()); i++) sb.append("\n  - ").append(problems.get(i));
        helper.fail(sb.toString());
    }
}

package com.tom.tradeoptimizer.gametest;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.trade.AvailableTrade;
import com.tom.tradeoptimizer.trade.OfferFactory;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.entity.npc.VillagerType;
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
 * #15 / #16: book cards sell what they say, and prices under vanillaPricing (a seeded price per
 * villager and book) are stable.
 *
 *  - #16: every card sells exactly the enchantment and level its key names. A book listing rolls
 *    from its own enchantment list (a biome tag under Trade Rebalance), not necessarily
 *    #minecraft:tradeable.
 *  - The price a card shows must be the price the villager charges: regeneration must not share
 *    one seeded stream across listings.
 *  - Single-level books (Mending, Silk Touch, curses, fixed-level specials) must get a real price
 *    roll: Minecraft skips the level roll when min == max, so a spare steering slot would be
 *    eaten by the price roll and pin it to the minimum.
 *
 * Librarians across every biome and rank. Game tests always run with trade_rebalance on (see
 * OwnershipGateGameTest), so pools differ by biome; the checks read what each card actually sells.
 */
public class BookPricingGameTest {
    private static final List<VillagerType> TYPES = List.of(VillagerType.PLAINS, VillagerType.DESERT,
            VillagerType.SAVANNA, VillagerType.TAIGA, VillagerType.SNOW, VillagerType.JUNGLE, VillagerType.SWAMP);

    @GameTest(timeoutTicks = 400, template = "fabric-gametest-api-v1:empty")
    public void bookPriceShownIsPriceCharged(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        boolean original = cfg.vanillaPricing();
        cfg.setVanillaPricing(true);
        List<String> problems = new ArrayList<>();
        int books = 0;
        try {
            for (VillagerType type : TYPES) {
                Villager v = librarian(helper, type);
                for (int lvl = 1; lvl <= 5; lvl++) {
                    for (AvailableTrade card : OfferFactory.enumerate(level, v, lvl)) {
                        if (!OfferFactory.isBookKey(card.key())) continue;
                        books++;
                        Optional<MerchantOffer> granted = OfferFactory.generate(level, v, card.key(), lvl);
                        if (granted.isEmpty()) {
                            problems.add(card.key().id() + " L" + lvl + ": not regenerated");
                        } else if (!samePrice(card.previewOffer(), granted.get())) {
                            problems.add(card.key().id() + " " + BuiltInRegistries.VILLAGER_TYPE.getKey(type).getPath() + " L" + lvl + ": card "
                                    + price(card.previewOffer()) + ", charged " + price(granted.get()));
                        }
                    }
                }
                v.discard();
            }
        } finally {
            cfg.setVanillaPricing(original);
        }
        helper.assertTrue(books > 0, "no book cards found on any librarian");
        report(helper, problems, books + " book cards");
    }

    @GameTest(timeoutTicks = 400, template = "fabric-gametest-api-v1:empty")
    public void singleLevelBooksGetARealPriceRoll(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        boolean original = cfg.vanillaPricing();
        cfg.setVanillaPricing(true);
        Map<String, List<Integer>> prices = new HashMap<>(); // single-level book card -> price per villager
        try {
            for (VillagerType type : TYPES) {
                for (int n = 0; n < 8; n++) {
                    Villager v = librarian(helper, type);
                    for (int lvl = 1; lvl <= 5; lvl++) {
                        for (AvailableTrade card : OfferFactory.enumerate(level, v, lvl)) {
                            if (OfferFactory.isBookKey(card.key()) && singleLevel(card.previewOffer().getResult())) {
                                prices.computeIfAbsent(BuiltInRegistries.VILLAGER_TYPE.getKey(type).getPath() + " " + card.key().id(), k -> new ArrayList<>())
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
        helper.assertTrue(!prices.isEmpty(), "no single-level enchanted book found in any librarian pool");
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
    @GameTest(timeoutTicks = 400, template = "fabric-gametest-api-v1:empty")
    public void bookCardsSellWhatTheySay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<String> problems = new ArrayList<>();
        int books = 0;
        for (VillagerType type : TYPES) {
            Villager v = librarian(helper, type);
            for (int lvl = 1; lvl <= 5; lvl++) {
                Map<String, Integer> seen = new HashMap<>();
                for (AvailableTrade card : OfferFactory.enumerate(level, v, lvl)) {
                    if (!OfferFactory.isBookKey(card.key())) continue;
                    books++;
                    String key = card.key().id().toString();
                    if (seen.merge(key, 1, Integer::sum) > 1) problems.add(BuiltInRegistries.VILLAGER_TYPE.getKey(type).getPath() + " L" + lvl + ": duplicate card " + key);
                    ItemEnchantments stored = card.previewOffer().getResult()
                            .getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
                    String sold = stored.entrySet().stream()
                            .map(e -> e.getKey().unwrapKey().map(k -> k.location().getPath()).orElse("?") + " " + e.getIntValue())
                            .reduce((x, y) -> x + ", " + y).orElse("nothing");
                    // key path: book/<level>/<ns>/<path>
                    String[] parts = card.key().id().getPath().split("/", 4);
                    String expected = parts[3] + " " + parts[1];
                    if (!sold.equals(expected)) problems.add(BuiltInRegistries.VILLAGER_TYPE.getKey(type).getPath() + " L" + lvl + ": card " + key + " sells " + sold);
                }
            }
            v.discard();
        }
        helper.assertTrue(books > 0, "no book cards found on any librarian");
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

    private static Villager librarian(GameTestHelper helper, VillagerType type) {
        var registries = helper.getLevel().registryAccess();
        Villager v = helper.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(1, 2, 1));
        v.setVillagerData(v.getVillagerData().setType(type)
                .setProfession(VillagerProfession.LIBRARIAN).setLevel(1));
        return v;
    }

    private static void report(GameTestHelper helper, List<String> problems, String scope) {
        if (problems.isEmpty()) { helper.succeed(); return; }
        StringBuilder sb = new StringBuilder(problems.size() + " problem(s), " + scope + " checked:");
        for (int i = 0; i < Math.min(12, problems.size()); i++) sb.append("\n  - ").append(problems.get(i));
        helper.fail(sb.toString());
    }
}

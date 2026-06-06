package com.tom.tradeoptimizer.trade;

import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Enumerate the trade pool for a (profession, level) pair and produce min-cost
 * MerchantOffers. Book trades expand into one card per (enchantment × level) so
 * the player can pick Sharpness V at min cost, not just Sharpness I.
 *
 * LootContext mirrors vanilla's exactly (matches AbstractVillager.addOffersFromTradeSet).
 * For book trades we feed vanilla's enchant_randomly an IndexBiasedRandomSource that
 * steers the enchantment + level rolls to the specific combination we want, with all
 * other random rolls (cost variance) pinned at 0.
 *
 * Synthetic TradeKey: tradeoptimizer:book/<ench_ns>/<ench_path>/L<level>
 */
public final class OfferFactory {
    private OfferFactory() {}

    private static final String BOOK_PREFIX = "book/";

    public static List<AvailableTrade> enumerate(ServerLevel level, Villager villager, ResourceKey<TradeSet> tradeSetKey) {
        List<AvailableTrade> out = new ArrayList<>();
        HolderLookup.Provider registries = level.registryAccess();

        Optional<Holder.Reference<TradeSet>> setRef =
                registries.lookupOrThrow(Registries.TRADE_SET).get(tradeSetKey);
        if (setRef.isEmpty()) {
            TradeOptimizer.LOGGER.warn("No TradeSet registered for key {}", tradeSetKey.identifier());
            return out;
        }
        TradeSet tradeSet = setRef.get().value();
        HolderSet<VillagerTrade> trades = tradeSet.getTrades();

        for (Holder<VillagerTrade> holder : trades) {
            Optional<ResourceKey<VillagerTrade>> keyOpt = holder.unwrapKey();
            if (keyOpt.isEmpty()) continue;

            try {
                LootContext minCtx = buildContext(level, villager, tradeSet, MinRandomSource.INSTANCE);
                MerchantOffer preview = holder.value().getOffer(minCtx);
                if (preview == null) continue;

                if (preview.getResult().is(Items.ENCHANTED_BOOK)) {
                    out.addAll(expandBookTrade(level, villager, tradeSet, holder.value(), registries));
                } else {
                    out.add(new AvailableTrade(new TradeKey(keyOpt.get().identifier()), preview));
                }
            } catch (Exception e) {
                TradeOptimizer.LOGGER.warn("Failed to generate preview for trade {}: {}",
                        keyOpt.get().identifier(), e.getMessage());
            }
        }
        return out;
    }

    public static Optional<MerchantOffer> generate(ServerLevel level, Villager villager, TradeKey key, int merchantLevel) {
        if (isBookKey(key)) {
            return generateBookOffer(level, villager, key, merchantLevel);
        }
        HolderLookup.Provider registries = level.registryAccess();
        Optional<Holder.Reference<VillagerTrade>> ref =
                registries.lookupOrThrow(Registries.VILLAGER_TRADE).get(key.asResourceKey());
        if (ref.isEmpty()) return Optional.empty();
        LootContext ctx = buildContext(level, villager, null, MinRandomSource.INSTANCE);
        try {
            return Optional.ofNullable(ref.get().value().getOffer(ctx));
        } catch (Exception e) {
            TradeOptimizer.LOGGER.warn("Failed to generate offer for trade {}: {}", key.id(), e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Book enumeration: one card per (enchantment × level)
    // -------------------------------------------------------------------------

    private static List<AvailableTrade> expandBookTrade(ServerLevel level, Villager villager,
                                                        TradeSet tradeSet, VillagerTrade template,
                                                        HolderLookup.Provider registries) {
        List<AvailableTrade> out = new ArrayList<>();
        List<Holder<Enchantment>> tradeable = tradeableEnchantments(registries);

        for (int enchIdx = 0; enchIdx < tradeable.size(); enchIdx++) {
            Holder<Enchantment> ench = tradeable.get(enchIdx);
            Optional<ResourceKey<Enchantment>> enchKey = ench.unwrapKey();
            if (enchKey.isEmpty()) continue;

            int minLvl = ench.value().getMinLevel();
            int maxLvl = ench.value().getMaxLevel();

            for (int lvl = minLvl; lvl <= maxLvl; lvl++) {
                int levelOffset = lvl - minLvl;
                try {
                    LootContext ctx = buildContext(level, villager, tradeSet,
                            new IndexBiasedRandomSource(enchIdx, levelOffset));
                    MerchantOffer offer = template.getOffer(ctx);
                    if (offer == null || !offer.getResult().is(Items.ENCHANTED_BOOK)) continue;
                    out.add(new AvailableTrade(
                            buildSyntheticBookKey(enchKey.get().identifier(), lvl), offer));
                } catch (Exception e) {
                    // Skip on failure — some enchantments may not be valid for this trade.
                }
            }
        }
        return out;
    }

    /** Villager merchant levels run 1 (Novice) .. 5 (Master). */
    private static final int MAX_MERCHANT_LEVEL = 5;

    private static Optional<MerchantOffer> generateBookOffer(ServerLevel level, Villager villager,
                                                             TradeKey synthetic, int merchantLevel) {
        SyntheticBookKey parsed = parseSyntheticBook(synthetic);
        if (parsed == null) return Optional.empty();

        HolderLookup.Provider registries = level.registryAccess();
        VillagerProfession prof = villager.getVillagerData().profession().value();

        // Find enchantment index + min level in the tradeable pool
        List<Holder<Enchantment>> tradeable = tradeableEnchantments(registries);
        int enchIdx = -1;
        int minLevel = 1;
        for (int i = 0; i < tradeable.size(); i++) {
            Holder<Enchantment> h = tradeable.get(i);
            if (h.unwrapKey().map(k -> k.identifier().equals(parsed.enchantmentId)).orElse(false)) {
                enchIdx = i;
                minLevel = h.value().getMinLevel();
                break;
            }
        }
        if (enchIdx < 0) return Optional.empty();
        int levelOffset = parsed.level - minLevel;

        // A book trade must be regenerated from a book-producing template. That template
        // has to come from a trade set that actually CONTAINS a book trade. The book was
        // picked at `merchantLevel`, so that set is the natural source — but a villager
        // may have advanced to a higher level whose set has NO book trade (e.g. a
        // librarian's master level sells candles, not books). Earlier this looked up the
        // villager's CURRENT level, so once it hit such a level every previously-picked
        // book silently failed to regenerate and got dropped. Try the pick's own level
        // first, then fall back to scanning every level for a usable book template.
        List<Integer> candidateLevels = new ArrayList<>();
        if (merchantLevel >= 1 && merchantLevel <= MAX_MERCHANT_LEVEL) candidateLevels.add(merchantLevel);
        for (int lvl = 1; lvl <= MAX_MERCHANT_LEVEL; lvl++) {
            if (!candidateLevels.contains(lvl)) candidateLevels.add(lvl);
        }

        for (int lvl : candidateLevels) {
            ResourceKey<TradeSet> tradeSetKey = prof.getTrades(lvl);
            if (tradeSetKey == null) continue;
            Optional<Holder.Reference<TradeSet>> setRef =
                    registries.lookupOrThrow(Registries.TRADE_SET).get(tradeSetKey);
            if (setRef.isEmpty()) continue;
            TradeSet tradeSet = setRef.get().value();

            for (Holder<VillagerTrade> holder : tradeSet.getTrades()) {
                String tradeName = holder.unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                try {
                    VillagerTrade trade = holder.value();
                    LootContext ctx = buildContext(level, villager, tradeSet,
                            new IndexBiasedRandomSource(enchIdx, levelOffset));
                    MerchantOffer offer = trade.getOffer(ctx);
                    if (offer == null || !offer.getResult().is(Items.ENCHANTED_BOOK)) continue;
                    TradeOptimizer.LOGGER.info("[book-regen] resolved {} -> {} via template {} (set level {})",
                            synthetic.id(), offer.getResult(), tradeName, lvl);
                    return Optional.of(offer);
                } catch (Exception e) {
                    TradeOptimizer.LOGGER.warn("[book-regen] template {} threw while regenerating {}",
                            tradeName, synthetic.id(), e);
                }
            }
        }
        TradeOptimizer.LOGGER.warn("[book-regen] FAILED to regenerate {} (no book template found at any level)",
                synthetic.id());
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Synthetic key encoding + tag helpers
    // -------------------------------------------------------------------------

    private record SyntheticBookKey(Identifier enchantmentId, int level) {}

    private static boolean isBookKey(TradeKey key) {
        return key.id().getNamespace().equals(TradeOptimizer.MOD_ID)
                && key.id().getPath().startsWith(BOOK_PREFIX);
    }

    private static TradeKey buildSyntheticBookKey(Identifier enchantId, int level) {
        String path = BOOK_PREFIX + enchantId.getNamespace() + "/" + enchantId.getPath() + "/L" + level;
        return new TradeKey(Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, path));
    }

    private static SyntheticBookKey parseSyntheticBook(TradeKey key) {
        // New format: "book/<ns>/<path>/L<level>"
        // Old format: "book/<ns>/<path>" — kept for backward-compat with profiles
        // saved before per-level expansion landed; default to level 1.
        String[] parts = key.id().getPath().split("/");
        if (parts.length == 3) {
            Identifier enchId = Identifier.fromNamespaceAndPath(parts[1], parts[2]);
            return new SyntheticBookKey(enchId, 1);
        }
        if (parts.length == 4 && parts[3].startsWith("L")) {
            try {
                int level = Integer.parseInt(parts[3].substring(1));
                Identifier enchId = Identifier.fromNamespaceAndPath(parts[1], parts[2]);
                return new SyntheticBookKey(enchId, level);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static List<Holder<Enchantment>> tradeableEnchantments(HolderLookup.Provider registries) {
        var enchRegistry = registries.lookupOrThrow(Registries.ENCHANTMENT);
        Optional<HolderSet.Named<Enchantment>> tag = enchRegistry.get(EnchantmentTags.TRADEABLE);
        if (tag.isEmpty()) return List.of();
        List<Holder<Enchantment>> out = new ArrayList<>();
        for (Holder<Enchantment> h : tag.get()) out.add(h);
        return out;
    }

    private static LootContext buildContext(ServerLevel level, Villager villager, TradeSet tradeSet, RandomSource rs) {
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, villager.position())
                .withParameter(LootContextParams.THIS_ENTITY, villager)
                .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
                .create(LootContextParamSets.VILLAGER_TRADE);

        Optional<Identifier> randomSeq = tradeSet != null ? tradeSet.randomSequence() : Optional.empty();

        return new LootContext.Builder(params)
                .withOptionalRandomSource(rs)
                .create(randomSeq);
    }
}

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
import net.minecraft.world.entity.npc.villager.VillagerData;
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
 * Server-side utility: enumerate the trade pool for a (profession, level) pair and
 * produce min-cost MerchantOffers.
 *
 * The LootContext mirrors vanilla's AbstractVillager.addOffersFromTradeSet exactly.
 * For non-book trades we plug in MinRandomSource so every NumberProvider lands at min.
 *
 * For book trades we ALSO use vanilla's getOffer pipeline — but with an
 * IndexBiasedRandomSource that returns the index of the enchantment we want for the
 * first nextInt (the one used by HolderSet.getRandomElement) and 0 for every
 * subsequent call (so level lands at min and the cost-variance roll lands at 0).
 *
 * That way vanilla itself computes the emerald cost using its real formula —
 * we never hard-code it — and the player gets the cheapest version vanilla would
 * ever roll for that specific enchantment.
 *
 * Synthetic TradeKey for book picks: tradeoptimizer:book/<ench_ns>/<ench_path>.
 * The server side resolves these by finding any enchanted-book trade in the current
 * villager's (profession, level) pool and using it as a template.
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
                // Generate one preview with MinRandomSource just to detect whether this
                // trade produces a book at all.
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

    public static Optional<MerchantOffer> generate(ServerLevel level, Villager villager, TradeKey key) {
        if (isBookKey(key)) {
            return generateBookOffer(level, villager, key);
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
    // Book enumeration via biased random source
    // -------------------------------------------------------------------------

    /** One AvailableTrade per #minecraft:tradeable enchantment, costs computed by vanilla. */
    private static List<AvailableTrade> expandBookTrade(ServerLevel level, Villager villager,
                                                        TradeSet tradeSet, VillagerTrade template,
                                                        HolderLookup.Provider registries) {
        List<AvailableTrade> out = new ArrayList<>();
        List<Holder<Enchantment>> tradeable = tradeableEnchantments(registries);

        for (int i = 0; i < tradeable.size(); i++) {
            Holder<Enchantment> ench = tradeable.get(i);
            Optional<ResourceKey<Enchantment>> enchKey = ench.unwrapKey();
            if (enchKey.isEmpty()) continue;

            try {
                LootContext ctx = buildContext(level, villager, tradeSet, new IndexBiasedRandomSource(i));
                MerchantOffer offer = template.getOffer(ctx);
                if (offer == null || !offer.getResult().is(Items.ENCHANTED_BOOK)) continue;
                out.add(new AvailableTrade(buildSyntheticBookKey(enchKey.get().identifier()), offer));
            } catch (Exception e) {
                TradeOptimizer.LOGGER.debug("Skipped enchantment {} in book expansion: {}",
                        enchKey.get().identifier(), e.getMessage());
            }
        }
        return out;
    }

    /** Server-side regen for a book pick: find the level's book template, bias random. */
    private static Optional<MerchantOffer> generateBookOffer(ServerLevel level, Villager villager, TradeKey synthetic) {
        Optional<Identifier> enchIdOpt = parseSyntheticEnchant(synthetic);
        if (enchIdOpt.isEmpty()) return Optional.empty();
        Identifier enchId = enchIdOpt.get();

        HolderLookup.Provider registries = level.registryAccess();
        VillagerData data = villager.getVillagerData();
        VillagerProfession prof = data.profession().value();
        ResourceKey<TradeSet> tradeSetKey = prof.getTrades(data.level());
        if (tradeSetKey == null) return Optional.empty();

        Optional<Holder.Reference<TradeSet>> setRef =
                registries.lookupOrThrow(Registries.TRADE_SET).get(tradeSetKey);
        if (setRef.isEmpty()) return Optional.empty();
        TradeSet tradeSet = setRef.get().value();

        // Find this enchantment's index in the tradeable pool.
        List<Holder<Enchantment>> tradeable = tradeableEnchantments(registries);
        int index = -1;
        for (int i = 0; i < tradeable.size(); i++) {
            if (tradeable.get(i).unwrapKey().map(k -> k.identifier().equals(enchId)).orElse(false)) {
                index = i;
                break;
            }
        }
        if (index < 0) return Optional.empty();

        // Find any book-producing trade in this level's set to use as the template.
        for (Holder<VillagerTrade> holder : tradeSet.getTrades()) {
            try {
                VillagerTrade trade = holder.value();
                LootContext ctx = buildContext(level, villager, tradeSet, new IndexBiasedRandomSource(index));
                MerchantOffer offer = trade.getOffer(ctx);
                if (offer != null && offer.getResult().is(Items.ENCHANTED_BOOK)) {
                    return Optional.of(offer);
                }
            } catch (Exception ignored) {
                // try the next trade
            }
        }
        return Optional.empty();
    }

    // -------------------------------------------------------------------------
    // Synthetic key encoding + tag helpers
    // -------------------------------------------------------------------------

    private static boolean isBookKey(TradeKey key) {
        return key.id().getNamespace().equals(TradeOptimizer.MOD_ID)
                && key.id().getPath().startsWith(BOOK_PREFIX);
    }

    private static TradeKey buildSyntheticBookKey(Identifier enchantId) {
        String path = BOOK_PREFIX + enchantId.getNamespace() + "/" + enchantId.getPath();
        return new TradeKey(Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, path));
    }

    private static Optional<Identifier> parseSyntheticEnchant(TradeKey key) {
        String[] parts = key.id().getPath().split("/", 3);
        if (parts.length != 3) return Optional.empty();
        return Optional.of(Identifier.fromNamespaceAndPath(parts[1], parts[2]));
    }

    private static List<Holder<Enchantment>> tradeableEnchantments(HolderLookup.Provider registries) {
        var enchRegistry = registries.lookupOrThrow(Registries.ENCHANTMENT);
        Optional<HolderSet.Named<Enchantment>> tag = enchRegistry.get(EnchantmentTags.TRADEABLE);
        if (tag.isEmpty()) return List.of();
        List<Holder<Enchantment>> out = new ArrayList<>();
        for (Holder<Enchantment> h : tag.get()) out.add(h);
        return out;
    }

    // -------------------------------------------------------------------------
    // LootContext construction (mirrors vanilla)
    // -------------------------------------------------------------------------

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

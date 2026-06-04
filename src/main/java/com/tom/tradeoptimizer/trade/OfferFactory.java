package com.tom.tradeoptimizer.trade;

import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.context.ContextKeySet;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.TradeSet;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Server-side utility: enumerate the trade pool for a (profession, level) pair and
 * produce min-cost MerchantOffers. Built on top of vanilla's VillagerTrade registry —
 * we don't define new trades, we just let the player pick from the existing ones.
 */
public final class OfferFactory {
    private OfferFactory() {}

    /** Empty context-key set — VillagerTrade.getOffer doesn't require entity params. */
    private static final ContextKeySet EMPTY_KEY_SET = new ContextKeySet.Builder().build();

    /** Enumerate all trades available at this level of this trade set. */
    public static List<AvailableTrade> enumerate(ServerLevel level, ResourceKey<TradeSet> tradeSetKey) {
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

        LootContext ctx = buildMinContext(level);

        for (Holder<VillagerTrade> holder : trades) {
            Optional<ResourceKey<VillagerTrade>> keyOpt = holder.unwrapKey();
            if (keyOpt.isEmpty()) continue;
            try {
                MerchantOffer preview = holder.value().getOffer(ctx);
                if (preview == null) {
                    // Vanilla trades can legitimately return null (e.g. dyed-armor with no valid
                    // color picked, map-trade with no nearby structure). Skip them.
                    continue;
                }
                out.add(new AvailableTrade(new TradeKey(keyOpt.get().identifier()), preview));
            } catch (Exception e) {
                TradeOptimizer.LOGGER.warn("Failed to generate preview for trade {}: {}",
                        keyOpt.get().identifier(), e.getMessage());
            }
        }
        return out;
    }

    /** Generate a fresh min-cost MerchantOffer for a single TradeKey. */
    public static Optional<MerchantOffer> generate(ServerLevel level, TradeKey key) {
        HolderLookup.Provider registries = level.registryAccess();
        Optional<Holder.Reference<VillagerTrade>> ref =
                registries.lookupOrThrow(Registries.VILLAGER_TRADE).get(key.asResourceKey());
        if (ref.isEmpty()) return Optional.empty();
        LootContext ctx = buildMinContext(level);
        try {
            return Optional.of(ref.get().value().getOffer(ctx));
        } catch (Exception e) {
            TradeOptimizer.LOGGER.warn("Failed to generate offer for trade {}: {}",
                    key.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private static LootContext buildMinContext(ServerLevel level) {
        LootParams params = new LootParams.Builder(level).create(EMPTY_KEY_SET);
        return new LootContext.Builder(params)
                .withOptionalRandomSource(MinRandomSource.INSTANCE)
                .create(Optional.empty());
    }
}

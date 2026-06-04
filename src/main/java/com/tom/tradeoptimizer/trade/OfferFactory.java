package com.tom.tradeoptimizer.trade;

import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.npc.villager.Villager;
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
 * The LootContext we build matches vanilla's exactly (mirrors AbstractVillager
 * .addOffersFromTradeSet), so enchant_randomly + additional_cost_component work
 * properly — without ORIGIN/THIS_ENTITY/ADDITIONAL_COST_COMPONENT_ALLOWED + the
 * VILLAGER_TRADE param set, enchanted-book trades silently become null.
 *
 * We swap in MinRandomSource at the last step so all NumberProvider rolls land at min.
 */
public final class OfferFactory {
    private OfferFactory() {}

    /** Enumerate all trades available at this level of this trade set. */
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

        LootContext ctx = buildMinContext(level, villager, tradeSet);

        for (Holder<VillagerTrade> holder : trades) {
            Optional<ResourceKey<VillagerTrade>> keyOpt = holder.unwrapKey();
            if (keyOpt.isEmpty()) continue;
            try {
                MerchantOffer preview = holder.value().getOffer(ctx);
                if (preview == null) continue;
                out.add(new AvailableTrade(new TradeKey(keyOpt.get().identifier()), preview));
            } catch (Exception e) {
                TradeOptimizer.LOGGER.warn("Failed to generate preview for trade {}: {}",
                        keyOpt.get().identifier(), e.getMessage());
            }
        }
        return out;
    }

    /** Generate a fresh min-cost MerchantOffer for a single TradeKey. */
    public static Optional<MerchantOffer> generate(ServerLevel level, Villager villager, TradeKey key) {
        HolderLookup.Provider registries = level.registryAccess();
        Optional<Holder.Reference<VillagerTrade>> ref =
                registries.lookupOrThrow(Registries.VILLAGER_TRADE).get(key.asResourceKey());
        if (ref.isEmpty()) return Optional.empty();
        // We don't have a TradeSet here, so pass null for the random sequence Optional.
        LootContext ctx = buildMinContext(level, villager, null);
        try {
            MerchantOffer offer = ref.get().value().getOffer(ctx);
            return Optional.ofNullable(offer);
        } catch (Exception e) {
            TradeOptimizer.LOGGER.warn("Failed to generate offer for trade {}: {}",
                    key.id(), e.getMessage());
            return Optional.empty();
        }
    }

    private static LootContext buildMinContext(ServerLevel level, Villager villager, TradeSet tradeSet) {
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, villager.position())
                .withParameter(LootContextParams.THIS_ENTITY, villager)
                .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
                .create(LootContextParamSets.VILLAGER_TRADE);

        Optional<net.minecraft.resources.Identifier> randomSeq = tradeSet != null
                ? tradeSet.randomSequence()
                : Optional.empty();

        return new LootContext.Builder(params)
                .withOptionalRandomSource(MinRandomSource.INSTANCE)
                .create(randomSeq);
    }
}

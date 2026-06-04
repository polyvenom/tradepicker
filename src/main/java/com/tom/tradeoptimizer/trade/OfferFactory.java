package com.tom.tradeoptimizer.trade;

import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
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
 * The LootContext mirrors vanilla's AbstractVillager.addOffersFromTradeSet exactly,
 * so enchant_randomly + additional_cost_component work properly. We swap in
 * MinRandomSource at the last step so all NumberProvider rolls land at min.
 *
 * Special case: enchanted-book trades are *expanded* into one preview card per
 * enchantment in the #minecraft:tradeable tag, each at the enchantment's min level
 * with the standard book-cost formula. We identify those expanded picks via a
 * synthetic TradeKey under the tradeoptimizer namespace (book/<ns>/<path>) so the
 * server's generate() knows to build them manually instead of going through the
 * vanilla VillagerTrade registry (which would re-randomize the enchantment).
 */
public final class OfferFactory {
    private OfferFactory() {}

    private static final String BOOK_PREFIX = "book/";

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

                // If this trade is an enchanted book, replace the single random-enchant entry
                // with one card per tradeable enchantment so the player chooses which one.
                if (preview.getResult().is(Items.ENCHANTED_BOOK)) {
                    out.addAll(expandBookTrade(registries, preview));
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

    /** Generate a fresh min-cost MerchantOffer for a single TradeKey (compound or vanilla). */
    public static Optional<MerchantOffer> generate(ServerLevel level, Villager villager, TradeKey key) {
        // Synthetic enchanted-book key: build manually with the chosen enchantment.
        if (key.id().getNamespace().equals(TradeOptimizer.MOD_ID)
                && key.id().getPath().startsWith(BOOK_PREFIX)) {
            return buildBookOfferFromSyntheticKey(level, key);
        }

        // Vanilla VillagerTrade key: defer to the registry + getOffer.
        HolderLookup.Provider registries = level.registryAccess();
        Optional<Holder.Reference<VillagerTrade>> ref =
                registries.lookupOrThrow(Registries.VILLAGER_TRADE).get(key.asResourceKey());
        if (ref.isEmpty()) return Optional.empty();
        LootContext ctx = buildMinContext(level, villager, null);
        try {
            return Optional.ofNullable(ref.get().value().getOffer(ctx));
        } catch (Exception e) {
            TradeOptimizer.LOGGER.warn("Failed to generate offer for trade {}: {}",
                    key.id(), e.getMessage());
            return Optional.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Enchanted-book handling
    // -------------------------------------------------------------------------

    /** Walk #minecraft:tradeable, build one preview card per enchantment at min level. */
    private static List<AvailableTrade> expandBookTrade(HolderLookup.Provider registries, MerchantOffer template) {
        List<AvailableTrade> out = new ArrayList<>();
        var enchRegistry = registries.lookupOrThrow(Registries.ENCHANTMENT);
        Optional<HolderSet.Named<Enchantment>> tradeable = enchRegistry.get(EnchantmentTags.TRADEABLE);
        if (tradeable.isEmpty()) return out;

        for (Holder<Enchantment> ench : tradeable.get()) {
            Optional<ResourceKey<Enchantment>> enchKey = ench.unwrapKey();
            if (enchKey.isEmpty()) continue;

            int level = ench.value().getMinLevel();
            int emeraldCost = computeBookCost(ench, level);

            ItemStack book = buildEnchantedBook(ench, level);

            MerchantOffer offer = new MerchantOffer(
                    new ItemCost(Items.EMERALD, emeraldCost),
                    Optional.of(new ItemCost(Items.BOOK, 1)),
                    book,
                    template.getUses(),
                    template.getMaxUses(),
                    template.getPriceMultiplier()
            );

            TradeKey synthetic = buildSyntheticBookKey(enchKey.get().identifier());
            out.add(new AvailableTrade(synthetic, offer));
        }
        return out;
    }

    /** Compute min emerald cost for an enchanted book using vanilla's standard formula. */
    private static int computeBookCost(Holder<Enchantment> ench, int level) {
        boolean treasure = ench.is(EnchantmentTags.TREASURE);
        int cost = treasure ? (2 + 7 * level) : (2 + 3 * level);
        if (ench.is(EnchantmentTags.DOUBLE_TRADE_PRICE)) cost *= 2;
        return Math.max(1, cost);
    }

    private static ItemStack buildEnchantedBook(Holder<Enchantment> ench, int level) {
        ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
        ItemEnchantments.Mutable enchants = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        enchants.set(ench, level);
        book.set(DataComponents.STORED_ENCHANTMENTS, enchants.toImmutable());
        return book;
    }

    /** Synthetic key format: tradeoptimizer:book/<enchant_namespace>/<enchant_path> */
    private static TradeKey buildSyntheticBookKey(Identifier enchantId) {
        String path = BOOK_PREFIX + enchantId.getNamespace() + "/" + enchantId.getPath();
        return new TradeKey(Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, path));
    }

    private static Optional<MerchantOffer> buildBookOfferFromSyntheticKey(ServerLevel level, TradeKey key) {
        // path is "book/<ns>/<path>"
        String[] parts = key.id().getPath().split("/", 3);
        if (parts.length != 3) return Optional.empty();
        Identifier enchId = Identifier.fromNamespaceAndPath(parts[1], parts[2]);
        ResourceKey<Enchantment> enchKey = ResourceKey.create(Registries.ENCHANTMENT, enchId);

        var enchRegistry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        Optional<Holder.Reference<Enchantment>> ref = enchRegistry.get(enchKey);
        if (ref.isEmpty()) return Optional.empty();

        Holder<Enchantment> ench = ref.get();
        int enchantLevel = ench.value().getMinLevel();
        int emeraldCost = computeBookCost(ench, enchantLevel);
        ItemStack book = buildEnchantedBook(ench, enchantLevel);

        // Standard librarian book trade defaults
        return Optional.of(new MerchantOffer(
                new ItemCost(Items.EMERALD, emeraldCost),
                Optional.of(new ItemCost(Items.BOOK, 1)),
                book,
                0, 12, 0.2f
        ));
    }

    // -------------------------------------------------------------------------
    // LootContext construction (mirrors vanilla)
    // -------------------------------------------------------------------------

    private static LootContext buildMinContext(ServerLevel level, Villager villager, TradeSet tradeSet) {
        LootParams params = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, villager.position())
                .withParameter(LootContextParams.THIS_ENTITY, villager)
                .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, Unit.INSTANCE)
                .create(LootContextParamSets.VILLAGER_TRADE);

        Optional<Identifier> randomSeq = tradeSet != null
                ? tradeSet.randomSequence()
                : Optional.empty();

        return new LootContext.Builder(params)
                .withOptionalRandomSource(MinRandomSource.INSTANCE)
                .create(randomSeq);
    }
}

package com.tom.tradeoptimizer.trade;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.config.TradeOptimizerConfig.GearEnchantMode;
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
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.enchantment.Enchantable;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

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
    private static final String GEAR_PREFIX = "gear/";
    private static final String ARROW_PREFIX = "arrow/";
    private static final String GEAR_SINGLE = "single/";
    private static final String GEAR_HEADLINE = "headline/";

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
                TradeKey flatKey = new TradeKey(keyOpt.get().identifier());
                LootContext ctx = buildContext(level, villager, tradeSet, costRandom(villager, flatKey));
                MerchantOffer preview = holder.value().getOffer(ctx);
                if (preview == null) continue;

                ItemStack result = preview.getResult();
                if (result.is(Items.ENCHANTED_BOOK)) {
                    out.addAll(expandBookTrade(level, villager, tradeSet, holder.value(), registries));
                } else if (result.is(Items.TIPPED_ARROW)) {
                    // Tipped arrows carry one potion effect, so they expand book-style: one card per potion.
                    List<AvailableTrade> arrows = expandArrowTrade(villager, preview, registries);
                    if (arrows.isEmpty()) out.add(new AvailableTrade(flatKey, preview));
                    else out.addAll(arrows);
                } else if (!result.getEnchantments().isEmpty()) {
                    // Enchanted gear (sword, bow, armor, tool, rod): vanilla rolls a random enchantment
                    // via the enchanting-table algorithm, so the picker only ever saw ONE card. Expand it
                    // into player-choosable cards (issues #4 / #5). Falls back to the single vanilla-rolled
                    // card if expansion can't run (e.g. the on_traded_equipment tag is missing).
                    List<AvailableTrade> gear = expandGearTrade(level, villager, preview, registries);
                    if (gear.isEmpty()) out.add(new AvailableTrade(flatKey, preview));
                    else out.addAll(gear);
                } else {
                    out.add(new AvailableTrade(flatKey, preview));
                }
            } catch (Exception e) {
                TradeOptimizer.LOGGER.warn("Failed to generate preview for trade {}: {}",
                        keyOpt.get().identifier(), e.getMessage());
            }
        }
        return out;
    }

    /**
     * How many enchanted-book trade TEMPLATES the (profession, level) trade set contains. Vanilla
     * grants at most this many book trades at that level — each template yields a single book trade —
     * so the picker uses it as the per-level book cap when {@code vanillaBookLimits} is on. This
     * counts the raw templates, NOT the per-(enchantment × level) cards {@link #enumerate} expands
     * each book template into.
     */
    public static int countBookTemplates(ServerLevel level, Villager villager, ResourceKey<TradeSet> tradeSetKey) {
        HolderLookup.Provider registries = level.registryAccess();
        Optional<Holder.Reference<TradeSet>> setRef =
                registries.lookupOrThrow(Registries.TRADE_SET).get(tradeSetKey);
        if (setRef.isEmpty()) return 0;
        TradeSet tradeSet = setRef.get().value();

        int count = 0;
        for (Holder<VillagerTrade> holder : tradeSet.getTrades()) {
            Optional<ResourceKey<VillagerTrade>> keyOpt = holder.unwrapKey();
            if (keyOpt.isEmpty()) continue;
            try {
                TradeKey flatKey = new TradeKey(keyOpt.get().identifier());
                LootContext ctx = buildContext(level, villager, tradeSet, costRandom(villager, flatKey));
                MerchantOffer preview = holder.value().getOffer(ctx);
                if (preview != null && preview.getResult().is(Items.ENCHANTED_BOOK)) count++;
            } catch (Exception e) {
                // Skip — same templates enumerate() would skip; not a book slot we can offer.
            }
        }
        return count;
    }

    public static Optional<MerchantOffer> generate(ServerLevel level, Villager villager, TradeKey key, int merchantLevel) {
        if (isBookKey(key)) {
            return generateBookOffer(level, villager, key, merchantLevel);
        }
        if (isGearKey(key)) {
            return generateGearOffer(level, villager, key, merchantLevel);
        }
        if (isArrowKey(key)) {
            return generateArrowOffer(level, villager, key, merchantLevel);
        }
        HolderLookup.Provider registries = level.registryAccess();
        Optional<Holder.Reference<VillagerTrade>> ref =
                registries.lookupOrThrow(Registries.VILLAGER_TRADE).get(key.asResourceKey());
        if (ref.isEmpty()) return Optional.empty();
        LootContext ctx = buildContext(level, villager, null, costRandom(villager, key));
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
                    TradeKey bookKey = buildSyntheticBookKey(enchKey.get().identifier(), lvl);
                    LootContext ctx = buildContext(level, villager, tradeSet,
                            new IndexBiasedRandomSource(bookCostFallback(villager, bookKey), enchIdx, levelOffset));
                    MerchantOffer offer = template.getOffer(ctx);
                    if (offer == null || !offer.getResult().is(Items.ENCHANTED_BOOK)) continue;
                    out.add(new AvailableTrade(bookKey, offer));
                } catch (Exception e) {
                    // Skip on failure — some enchantments may not be valid for this trade.
                }
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Enchanted gear enumeration: cards per (enchantment [× level])
    // -------------------------------------------------------------------------

    /** Vanilla villager enchanted-gear trades roll the enchant level in this base range (5..19). */
    private static final int GEAR_BASE_LEVEL_MIN = 5;
    private static final int GEAR_BASE_LEVEL_MAX = 19;
    /** Bounded retry budget for steering a headline roll to include the chosen enchantment. */
    private static final int HEADLINE_ATTEMPTS = 256;
    /** Emerald price ceiling when cost-scaling is on — vanilla never charges more than this. */
    private static final int EMERALD_CAP = 64;

    /** One enchantment + level, used to total a scaled price. */
    private record EnchPick(Holder<Enchantment> ench, int level) {}

    /**
     * Cost/shape carried over from the vanilla gear (or tipped-arrow) trade template: everything about
     * the offer EXCEPT the enchantment/potion, which we choose. Lets a hand-built offer keep vanilla's
     * emerald cost, secondary cost, stock, XP and price multiplier.
     */
    private record GearTemplateInfo(ItemCost costA, Optional<ItemCost> costB, int count,
                                    int maxUses, int xp, float priceMultiplier) {
        static GearTemplateInfo from(MerchantOffer o) {
            return new GearTemplateInfo(o.getItemCostA(), o.getItemCostB(), o.getResult().getCount(),
                    o.getMaxUses(), o.getXp(), o.getPriceMultiplier());
        }
    }

    private static List<AvailableTrade> expandGearTrade(ServerLevel level, Villager villager,
                                                        MerchantOffer templatePreview, HolderLookup.Provider registries) {
        List<AvailableTrade> out = new ArrayList<>();
        Optional<HolderSet<Enchantment>> poolOpt = onTradedPool(registries);
        if (poolOpt.isEmpty()) return out;
        HolderSet<Enchantment> pool = poolOpt.get();

        Item item = templatePreview.getResult().getItem();
        Identifier itemId = BuiltInRegistries.ITEM.getKey(item);
        GearTemplateInfo tmpl = GearTemplateInfo.from(templatePreview);
        String profId = professionId(villager);
        ItemStack base = new ItemStack(item, tmpl.count());

        // What enchantments (and at what max level) vanilla can actually roll on this item, derived from
        // vanilla's own getAvailableEnchantmentResults across the villager modified-level range. Stays
        // exactly within the vanilla pool, so we never offer something a vanilla trade couldn't produce.
        Map<Holder<Enchantment>, Integer> reachable = reachableGearEnchants(base, pool);
        if (reachable.isEmpty()) return out;

        // Stable card order: alphabetical by enchantment id.
        List<Map.Entry<Holder<Enchantment>, Integer>> entries = new ArrayList<>(reachable.entrySet());
        entries.sort((a, b) -> enchId(a.getKey()).compareTo(enchId(b.getKey())));

        GearEnchantMode mode = TradeOptimizerConfig.get().gearEnchantMode();

        for (Map.Entry<Holder<Enchantment>, Integer> e : entries) {
            Holder<Enchantment> ench = e.getKey();
            Optional<ResourceKey<Enchantment>> ek = ench.unwrapKey();
            if (ek.isEmpty()) continue;
            Identifier enchId = ek.get().identifier();

            if (mode == GearEnchantMode.SINGLE) {
                int maxLvl = e.getValue();
                for (int lvl = ench.value().getMinLevel(); lvl <= maxLvl; lvl++) {
                    TradeKey key = gearSingleKey(itemId, enchId, lvl);
                    MerchantOffer offer = buildGearOffer(level, villager, tmpl, item, profId,
                            GearEnchantMode.SINGLE, ench, lvl, registries, key, pool);
                    if (offer != null) out.add(new AvailableTrade(key, offer));
                }
            } else {
                // HEADLINE (also the fallback for the not-yet-shipped COMBO mode): one card per
                // enchantment. The level + any bonus enchantments are rolled by vanilla, deterministically
                // per villager, so the preview card shows exactly what the trade will grant.
                TradeKey key = gearHeadlineKey(itemId, enchId);
                MerchantOffer offer = buildGearOffer(level, villager, tmpl, item, profId,
                        GearEnchantMode.HEADLINE, ench, 1, registries, key, pool);
                if (offer != null) out.add(new AvailableTrade(key, offer));
            }
        }
        return out;
    }

    /**
     * Every (enchantment → max level) vanilla can roll on {@code base} via the enchanting-table
     * algorithm at villager tier. Walks the modified-level range vanilla's roll can land in and asks
     * vanilla's own {@link EnchantmentHelper#getAvailableEnchantmentResults} which enchantments are
     * eligible at each step — so the set is exactly what a vanilla trade could produce, no more.
     */
    private static Map<Holder<Enchantment>, Integer> reachableGearEnchants(ItemStack base, HolderSet<Enchantment> pool) {
        Map<Holder<Enchantment>, Integer> max = new LinkedHashMap<>();
        Enchantable enchantable = base.get(DataComponents.ENCHANTABLE);
        if (enchantable == null) return max;
        int enchantability = enchantable.value();
        int spread = enchantability / 4;
        // Mirrors EnchantmentHelper's modified-level formula bounds: base level +1 + two 0..spread rolls,
        // then ±15% variance. Min uses the lowest base level / all-min rolls, max the highest / all-max.
        int pMin = Math.max(1, Math.round((GEAR_BASE_LEVEL_MIN + 1) * 0.85f));
        int pMax = Math.round((GEAR_BASE_LEVEL_MAX + 1 + 2 * spread) * 1.15f);
        for (int p = pMin; p <= pMax; p++) {
            List<EnchantmentInstance> avail;
            try {
                avail = EnchantmentHelper.getAvailableEnchantmentResults(p, base, pool.stream());
            } catch (Exception ex) {
                continue;
            }
            for (EnchantmentInstance inst : avail) {
                max.merge(inst.enchantment(), inst.level(), Math::max);
            }
        }
        return max;
    }

    /**
     * Build one enchanted-gear offer by hand. SINGLE applies exactly the chosen enchantment + level.
     * HEADLINE replays vanilla's enchant roll (seeded per villager + trade so preview == apply), forcing
     * the chosen enchantment to be present and letting vanilla decide its level + any bonus enchantments.
     */
    private static MerchantOffer buildGearOffer(ServerLevel level, Villager villager, GearTemplateInfo tmpl,
                                                Item item, String profId, GearEnchantMode mode,
                                                Holder<Enchantment> ench, int singleLevel,
                                                HolderLookup.Provider registries, TradeKey key,
                                                HolderSet<Enchantment> pool) {
        ItemStack result = new ItemStack(item, tmpl.count());
        List<EnchPick> finalSet = new ArrayList<>();

        if (mode == GearEnchantMode.SINGLE) {
            result.enchant(ench, singleLevel);
            finalSet.add(new EnchPick(ench, singleLevel));
        } else {
            ItemStack rolled = rollHeadline(level, result, ench, priceSeed(villager, key), registries, pool);
            if (rolled == null) return null;
            result = rolled;
            ItemEnchantments ie = result.getEnchantments();
            for (Holder<Enchantment> h : ie.keySet()) finalSet.add(new EnchPick(h, ie.getLevel(h)));
        }

        ItemCost costA = TradeOptimizerConfig.get().isCostScaling(profId)
                ? scaledCost(finalSet)
                : tmpl.costA();
        return new MerchantOffer(costA, tmpl.costB(), result, tmpl.maxUses(), tmpl.xp(), tmpl.priceMultiplier());
    }

    /**
     * Roll an enchanted item the vanilla way but guarantee {@code headline} ends up on it. Retries
     * vanilla's {@link EnchantmentHelper#enchantItem} with a deterministic seed sequence until the
     * headline enchantment appears; if it never does (rare, low-weight pairing) it force-applies the
     * headline alone. Deterministic per seed, so the picker preview and the granted offer always match.
     */
    private static ItemStack rollHeadline(ServerLevel level, ItemStack base, Holder<Enchantment> headline,
                                          long seed, HolderLookup.Provider registries, HolderSet<Enchantment> pool) {
        Identifier targetId = headline.unwrapKey().map(ResourceKey::identifier).orElse(null);
        if (targetId == null) return null;
        Optional<? extends HolderSet<Enchantment>> poolOpt = Optional.of(pool);
        for (int i = 0; i < HEADLINE_ATTEMPTS; i++) {
            RandomSource r = RandomSource.create(seed * 31L + i);
            int lvl = GEAR_BASE_LEVEL_MIN + r.nextInt(GEAR_BASE_LEVEL_MAX - GEAR_BASE_LEVEL_MIN + 1);
            ItemStack out;
            try {
                out = EnchantmentHelper.enchantItem(r, base.copy(), lvl, level.registryAccess(), poolOpt);
            } catch (Exception ex) {
                continue;
            }
            if (hasEnchant(out, targetId)) return out;
        }
        ItemStack out = base.copy();
        out.enchant(headline, headline.value().getMinLevel());
        return out;
    }

    private static boolean hasEnchant(ItemStack stack, Identifier id) {
        for (Holder<Enchantment> h : stack.getEnchantments().keySet()) {
            if (h.unwrapKey().map(k -> k.identifier().equals(id)).orElse(false)) return true;
        }
        return false;
    }

    /** Scaled emerald cost: rarer (higher anvil cost) and higher-level enchantments cost more. */
    private static ItemCost scaledCost(List<EnchPick> picks) {
        int total = 2;
        for (EnchPick p : picks) {
            total += Math.max(1, p.ench().value().getAnvilCost()) * p.level();
        }
        total = Math.max(1, Math.min(EMERALD_CAP, total));
        return new ItemCost(Items.EMERALD, total);
    }

    // -------------------------------------------------------------------------
    // Tipped-arrow enumeration: one card per potion (book-style, single effect)
    // -------------------------------------------------------------------------

    private static List<AvailableTrade> expandArrowTrade(Villager villager, MerchantOffer templatePreview,
                                                         HolderLookup.Provider registries) {
        List<AvailableTrade> out = new ArrayList<>();
        GearTemplateInfo tmpl = GearTemplateInfo.from(templatePreview);
        registries.lookupOrThrow(Registries.POTION).listElements().forEach(h -> {
            Potion p = h.value();
            if (p.getEffects().isEmpty()) return; // skip water / mundane / awkward / thick (no effect)
            Optional<ResourceKey<Potion>> pk = h.unwrapKey();
            if (pk.isEmpty()) return;
            TradeKey key = arrowKey(pk.get().identifier());
            out.add(new AvailableTrade(key, buildArrowOffer(tmpl, h)));
        });
        return out;
    }

    private static MerchantOffer buildArrowOffer(GearTemplateInfo tmpl, Holder<Potion> potion) {
        ItemStack arrow = new ItemStack(Items.TIPPED_ARROW, tmpl.count());
        arrow.set(DataComponents.POTION_CONTENTS, new PotionContents(potion));
        return new MerchantOffer(tmpl.costA(), tmpl.costB(), arrow, tmpl.maxUses(), tmpl.xp(), tmpl.priceMultiplier());
    }

    // -------------------------------------------------------------------------
    // Gear / arrow offer regeneration (apply from stored picks)
    // -------------------------------------------------------------------------

    private static Optional<MerchantOffer> generateGearOffer(ServerLevel level, Villager villager,
                                                             TradeKey key, int merchantLevel) {
        GearKey gk = parseGearKey(key);
        if (gk == null) return Optional.empty();
        HolderLookup.Provider registries = level.registryAccess();

        Item item = registries.lookupOrThrow(Registries.ITEM)
                .get(ResourceKey.create(Registries.ITEM, gk.item()))
                .map(Holder.Reference::value).orElse(null);
        Optional<Holder.Reference<Enchantment>> ench = registries.lookupOrThrow(Registries.ENCHANTMENT)
                .get(ResourceKey.create(Registries.ENCHANTMENT, gk.ench()));
        if (item == null || ench.isEmpty()) return Optional.empty();

        Optional<HolderSet<Enchantment>> pool = onTradedPool(registries);
        if (pool.isEmpty()) return Optional.empty();

        GearTemplateInfo tmpl = findTemplate(level, villager, merchantLevel,
                o -> o.getResult().getItem() == item && !o.getResult().getEnchantments().isEmpty());
        if (tmpl == null) {
            TradeOptimizer.LOGGER.warn("[gear-regen] no gear template found for {}", key.id());
            return Optional.empty();
        }
        MerchantOffer offer = buildGearOffer(level, villager, tmpl, item, professionId(villager),
                gk.mode(), ench.get(), gk.level(), registries, key, pool.get());
        return Optional.ofNullable(offer);
    }

    private static Optional<MerchantOffer> generateArrowOffer(ServerLevel level, Villager villager,
                                                              TradeKey key, int merchantLevel) {
        Identifier potionId = parseArrowKey(key);
        if (potionId == null) return Optional.empty();
        HolderLookup.Provider registries = level.registryAccess();
        Optional<Holder.Reference<Potion>> potion = registries.lookupOrThrow(Registries.POTION)
                .get(ResourceKey.create(Registries.POTION, potionId));
        if (potion.isEmpty()) return Optional.empty();

        GearTemplateInfo tmpl = findTemplate(level, villager, merchantLevel,
                o -> o.getResult().is(Items.TIPPED_ARROW));
        if (tmpl == null) {
            TradeOptimizer.LOGGER.warn("[arrow-regen] no tipped-arrow template found for {}", key.id());
            return Optional.empty();
        }
        return Optional.of(buildArrowOffer(tmpl, potion.get()));
    }

    /**
     * Find a vanilla trade template across this villager's levels whose min-cost preview matches the
     * given predicate, returning its cost/shape. Mirrors the book-regen level scan: the pick's own level
     * first, then every level — so a pick still regenerates even after the villager advanced past it.
     */
    private static GearTemplateInfo findTemplate(ServerLevel level, Villager villager, int merchantLevel,
                                                 Predicate<MerchantOffer> match) {
        HolderLookup.Provider registries = level.registryAccess();
        VillagerProfession prof = villager.getVillagerData().profession().value();

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
                Optional<ResourceKey<VillagerTrade>> keyOpt = holder.unwrapKey();
                if (keyOpt.isEmpty()) continue;
                try {
                    TradeKey fk = new TradeKey(keyOpt.get().identifier());
                    LootContext ctx = buildContext(level, villager, tradeSet, costRandom(villager, fk));
                    MerchantOffer preview = holder.value().getOffer(ctx);
                    if (preview != null && match.test(preview)) return GearTemplateInfo.from(preview);
                } catch (Exception e) {
                    // skip — same templates enumerate() would skip
                }
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Gear / arrow synthetic key codec
    // -------------------------------------------------------------------------

    /** Parsed gear key: which item, which enchantment, the mode, and (SINGLE only) the level. */
    private record GearKey(GearEnchantMode mode, Identifier item, Identifier ench, int level) {}

    public static boolean isGearKey(TradeKey key) {
        return key.id().getNamespace().equals(TradeOptimizer.MOD_ID)
                && key.id().getPath().startsWith(GEAR_PREFIX);
    }

    public static boolean isArrowKey(TradeKey key) {
        return key.id().getNamespace().equals(TradeOptimizer.MOD_ID)
                && key.id().getPath().startsWith(ARROW_PREFIX);
    }

    /**
     * For a HEADLINE gear key, the enchantment the player chose (the rest of the rolled enchantments are
     * vanilla bonuses). Empty for SINGLE / non-gear keys. Used by the picker UI to label the card.
     */
    public static Optional<Identifier> headlineEnchantId(TradeKey key) {
        if (!isGearKey(key)) return Optional.empty();
        GearKey gk = parseGearKey(key);
        if (gk == null || gk.mode() != GearEnchantMode.HEADLINE) return Optional.empty();
        return Optional.of(gk.ench());
    }

    static TradeKey gearSingleKey(Identifier item, Identifier ench, int level) {
        StringBuilder sb = new StringBuilder(GEAR_PREFIX).append(GEAR_SINGLE).append(level).append('/');
        appendId(sb, item);
        sb.append('/');
        appendId(sb, ench);
        return new TradeKey(Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, sb.toString()));
    }

    static TradeKey gearHeadlineKey(Identifier item, Identifier ench) {
        StringBuilder sb = new StringBuilder(GEAR_PREFIX).append(GEAR_HEADLINE);
        appendId(sb, item);
        sb.append('/');
        appendId(sb, ench);
        return new TradeKey(Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, sb.toString()));
    }

    static TradeKey arrowKey(Identifier potion) {
        StringBuilder sb = new StringBuilder(ARROW_PREFIX);
        appendId(sb, potion);
        return new TradeKey(Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, sb.toString()));
    }

    /**
     * Length-prefixed Identifier encoding so two identifiers (item + enchantment) pack into one
     * Identifier path unambiguously even though a modded path may itself contain '/'. Form:
     * {@code <segCount>/<namespace>/<pathSeg0>/<pathSeg1>...} where segCount = 1 + number of path
     * segments. Every emitted character is a valid Identifier path character.
     */
    private static void appendId(StringBuilder sb, Identifier id) {
        String[] pathSegs = id.getPath().split("/");
        sb.append(1 + pathSegs.length).append('/').append(id.getNamespace());
        for (String seg : pathSegs) sb.append('/').append(seg);
    }

    /** Read one length-prefixed Identifier starting at {@code cur[0]}, advancing the cursor past it. */
    private static Identifier readId(String[] parts, int[] cur) {
        if (cur[0] >= parts.length) return null;
        int count;
        try {
            count = Integer.parseInt(parts[cur[0]++]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (count < 2 || cur[0] + count > parts.length) return null;
        String ns = parts[cur[0]++];
        StringBuilder path = new StringBuilder();
        for (int i = 1; i < count; i++) {
            if (i > 1) path.append('/');
            path.append(parts[cur[0]++]);
        }
        try {
            return Identifier.fromNamespaceAndPath(ns, path.toString());
        } catch (Exception e) {
            return null;
        }
    }

    static GearKey parseGearKey(TradeKey key) {
        String path = key.id().getPath();
        if (!path.startsWith(GEAR_PREFIX)) return null;
        String rest = path.substring(GEAR_PREFIX.length());
        GearEnchantMode mode;
        String body;
        if (rest.startsWith(GEAR_SINGLE)) {
            mode = GearEnchantMode.SINGLE;
            body = rest.substring(GEAR_SINGLE.length());
        } else if (rest.startsWith(GEAR_HEADLINE)) {
            mode = GearEnchantMode.HEADLINE;
            body = rest.substring(GEAR_HEADLINE.length());
        } else {
            return null;
        }
        String[] parts = body.split("/");
        int[] cur = {0};
        int level = 1;
        if (mode == GearEnchantMode.SINGLE) {
            if (cur[0] >= parts.length) return null;
            try {
                level = Integer.parseInt(parts[cur[0]++]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Identifier item = readId(parts, cur);
        Identifier ench = readId(parts, cur);
        if (item == null || ench == null) return null;
        return new GearKey(mode, item, ench, level);
    }

    static Identifier parseArrowKey(TradeKey key) {
        String path = key.id().getPath();
        if (!path.startsWith(ARROW_PREFIX)) return null;
        String[] parts = path.substring(ARROW_PREFIX.length()).split("/");
        return readId(parts, new int[]{0});
    }

    private static Optional<HolderSet<Enchantment>> onTradedPool(HolderLookup.Provider registries) {
        var reg = registries.lookupOrThrow(Registries.ENCHANTMENT);
        return reg.get(EnchantmentTags.ON_TRADED_EQUIPMENT).map(t -> (HolderSet<Enchantment>) t);
    }

    private static String professionId(Villager villager) {
        return BuiltInRegistries.VILLAGER_PROFESSION
                .getKey(villager.getVillagerData().profession().value()).toString();
    }

    private static String enchId(Holder<Enchantment> ench) {
        return ench.unwrapKey().map(k -> k.identifier().toString()).orElse("");
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
        RandomSource costFallback = bookCostFallback(villager, synthetic);

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
                            new IndexBiasedRandomSource(costFallback, enchIdx, levelOffset));
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

    // Package-private (not private) so BookKeyFormatGameTest can round-trip the key encoding.
    record SyntheticBookKey(Identifier enchantmentId, int level) {}

    public static boolean isBookKey(TradeKey key) {
        return key.id().getNamespace().equals(TradeOptimizer.MOD_ID)
                && key.id().getPath().startsWith(BOOK_PREFIX);
    }

    // Package-private (not private) so BookKeyFormatGameTest can verify the written format.
    static TradeKey buildSyntheticBookKey(Identifier enchantId, int level) {
        // Level-first, all-lowercase form: book/<level>/<ench_ns>/<ench_path>. The level leads so
        // parsing stays unambiguous even though <ench_path> may contain slashes, and every
        // character is a valid Identifier path char. The old form put the level last as an
        // uppercase "L<level>" marker — which strict Identifier validation rejects (headless tests,
        // and potentially other loaders), so it only ever worked thanks to the live game being
        // lenient. parseSyntheticBook still reads the old form for pre-migration saves.
        String path = BOOK_PREFIX + level + "/" + enchantId.getNamespace() + "/" + enchantId.getPath();
        return new TradeKey(Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, path));
    }

    // Package-private (not private) so BookKeyFormatGameTest can round-trip the key encoding.
    static SyntheticBookKey parseSyntheticBook(TradeKey key) {
        // NEW format (current):  book/<level>/<ench_ns>/<ench_path>   — level-first, all lowercase.
        // OLD format (pre-migration saves):  book/<ench_ns>/<ench_path>/L<level>   — uppercase 'L'
        //   marker. Still read so existing villagers keep their book picks.
        // LEGACY format:  book/<ench_ns>/<ench_path>   (no level marker) — pre-dates per-level
        //   expansion; treated as level 1.
        // <ench_path> may itself contain '/', because modded enchantments are allowed slashes in
        // their path — so we can't assume a fixed segment count.
        String path = key.id().getPath();
        if (!path.startsWith(BOOK_PREFIX)) return null;

        String[] parts = path.substring(BOOK_PREFIX.length()).split("/");
        if (parts.length < 2) return null; // need at least <ns> and one path segment

        // NEW format first: a purely-numeric leading segment is the level. Real enchantment
        // namespaces are never purely numeric, so this can't be mistaken for the OLD/legacy forms
        // below (whose leading segment is the namespace).
        if (parts.length >= 3 && isAllDigits(parts[0])) {
            int newLevel;
            try {
                newLevel = Integer.parseInt(parts[0]);
            } catch (NumberFormatException e) {
                return null;
            }
            String newNs = parts[1];
            StringBuilder newPath = new StringBuilder();
            for (int i = 2; i < parts.length; i++) {
                if (i > 2) newPath.append('/');
                newPath.append(parts[i]);
            }
            if (newPath.length() == 0) return null;
            try {
                return new SyntheticBookKey(Identifier.fromNamespaceAndPath(newNs, newPath.toString()), newLevel);
            } catch (Exception e) {
                return null;
            }
        }

        String ns = parts[0];
        int lastIdx = parts.length - 1;

        int level = 1;
        int pathEndExclusive = parts.length;
        String last = parts[lastIdx];
        // A trailing "L<number>" segment is the level marker — only when there's a real
        // path segment before it (lastIdx >= 2), so a 2-part legacy key isn't misread.
        if (lastIdx >= 2 && last.length() > 1 && last.charAt(0) == 'L') {
            try {
                level = Integer.parseInt(last.substring(1));
                pathEndExclusive = lastIdx; // drop the marker; the rest is the ench path
            } catch (NumberFormatException e) {
                return null; // looked like a marker but wasn't a number — malformed
            }
        }

        StringBuilder enchPath = new StringBuilder();
        for (int i = 1; i < pathEndExclusive; i++) {
            if (i > 1) enchPath.append('/');
            enchPath.append(parts[i]);
        }
        if (enchPath.length() == 0) return null;

        try {
            Identifier enchId = Identifier.fromNamespaceAndPath(ns, enchPath.toString());
            return new SyntheticBookKey(enchId, level);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
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
    // Pricing source selection (config-driven)
    // -------------------------------------------------------------------------

    /**
     * Stable per-(villager, trade) seed. Hashing the TradeKey's string id keeps the value
     * identical between the picker preview and the eventual apply, and across re-opens — so a
     * randomized price can't be re-rolled by reopening the menu.
     */
    private static long priceSeed(Villager villager, TradeKey key) {
        return villager.getUUID().getMostSignificantBits() * 31L + key.id().toString().hashCode();
    }

    /** Cost source for flat (non-book) trades: min cost by default, seeded vanilla range if enabled. */
    private static RandomSource costRandom(Villager villager, TradeKey key) {
        return TradeOptimizerConfig.get().vanillaPricing()
                ? RandomSource.create(priceSeed(villager, key))
                : MinRandomSource.INSTANCE;
    }

    /**
     * Cost fallback handed to {@link IndexBiasedRandomSource} for book trades. Returns null in
     * min-price mode (so cost rolls pin to 0), or a seeded source when vanilla pricing is on —
     * the steered enchantment/level prefix is honored either way.
     */
    private static RandomSource bookCostFallback(Villager villager, TradeKey key) {
        return TradeOptimizerConfig.get().vanillaPricing()
                ? RandomSource.create(priceSeed(villager, key))
                : null;
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

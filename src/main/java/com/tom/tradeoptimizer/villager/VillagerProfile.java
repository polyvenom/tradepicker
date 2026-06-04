package com.tom.tradeoptimizer.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-villager record of how each level was filled.
 *
 *   id          — the villager's UUID
 *   profession  — registry name string, sanity check
 *   picks       — levels the player explicitly chose: map<level, TradeKeys>
 *   legacy      — levels we imported from a villager that already had vanilla-rolled
 *                 trades before the mod was installed: map<level, raw MerchantOffers>
 *
 * A level is "filled" if EITHER picks or legacy has entries for it. When the player
 * Resets, both lanes get wiped.
 */
public record VillagerProfile(
        UUID id,
        String profession,
        Map<Integer, List<TradeKey>> picks,
        Map<Integer, List<MerchantOffer>> legacy
) {

    public VillagerProfile {
        picks = new HashMap<>(picks);
        legacy = new HashMap<>(legacy);
    }

    private static final Codec<Integer> INT_STR_CODEC =
            Codec.STRING.xmap(Integer::parseInt, String::valueOf);

    public static final Codec<VillagerProfile> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(VillagerProfile::id),
            Codec.STRING.fieldOf("prof").forGetter(VillagerProfile::profession),
            Codec.unboundedMap(INT_STR_CODEC, TradeKey.CODEC.listOf())
                    .optionalFieldOf("picks", new HashMap<>()).forGetter(VillagerProfile::picks),
            Codec.unboundedMap(INT_STR_CODEC, MerchantOffer.CODEC.listOf())
                    .optionalFieldOf("legacy", new HashMap<>()).forGetter(VillagerProfile::legacy)
    ).apply(inst, VillagerProfile::new));

    /** A level is filled if either lane has entries for it. */
    public boolean isFilled(int level) {
        List<TradeKey> p = picks.get(level);
        if (p != null && !p.isEmpty()) return true;
        List<MerchantOffer> l = legacy.get(level);
        return l != null && !l.isEmpty();
    }

    public List<TradeKey> picksFor(int level) {
        return picks.getOrDefault(level, List.of());
    }

    public List<MerchantOffer> legacyFor(int level) {
        return legacy.getOrDefault(level, List.of());
    }

    public void setPicks(int level, List<TradeKey> p) {
        // Picks override legacy for the same level — if the player picks fresh, drop the imports.
        legacy.remove(level);
        picks.put(level, List.copyOf(p));
    }

    public void setLegacy(int level, List<MerchantOffer> offers) {
        legacy.put(level, List.copyOf(offers));
    }

    public void clearAll() {
        picks.clear();
        legacy.clear();
    }

    /** True if no level has been picked or imported yet. */
    public boolean isEmpty() {
        return picks.isEmpty() && legacy.isEmpty();
    }

    public static VillagerProfile fresh(UUID id, String profession) {
        return new VillagerProfile(id, profession, new HashMap<>(), new HashMap<>());
    }
}

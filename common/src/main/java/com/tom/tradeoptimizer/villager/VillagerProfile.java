package com.tom.tradeoptimizer.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.item.trading.MerchantOffer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Per-villager record of how each level was filled.
 *
 *   id          — the villager's UUID
 *   profession  — registry name string, sanity check
 *   owner       — the player who first claimed the villager; only they (or an op) may reset.
 *                 Empty for profiles saved before ownership existed: the next player to
 *                 right-click claims it.
 *   picks       — levels the player chose: map<level, TradeKeys>
 *   legacy      — levels imported from vanilla-rolled offers the villager had before the mod:
 *                 map<level, raw MerchantOffers>
 *
 * A level is "filled" if either lane has entries for it. Reset wipes both, keeping the owner.
 */
public record VillagerProfile(
        UUID id,
        String profession,
        Optional<UUID> owner,
        Map<Integer, List<TradeKey>> picks,
        Map<Integer, List<MerchantOffer>> legacy
) {

    public VillagerProfile {
        picks = new HashMap<>(picks);
        legacy = new HashMap<>(legacy);
    }

    /**
     * Level-keyed map codec. Levels are string keys on disk; parsing per entry (not through a key
     * codec) means one corrupted or hand-edited key is dropped and logged instead of throwing
     * mid-decode and losing the whole profile.
     */
    private static <V> Codec<Map<Integer, V>> levelMapCodec(Codec<V> valueCodec) {
        return Codec.unboundedMap(Codec.STRING, valueCodec).xmap(
                raw -> {
                    Map<Integer, V> out = new HashMap<>();
                    for (Map.Entry<String, V> e : raw.entrySet()) {
                        try {
                            out.put(Integer.parseInt(e.getKey()), e.getValue());
                        } catch (NumberFormatException ex) {
                            TradeOptimizer.LOGGER.warn(
                                    "Dropping villager-profile entry under malformed level key '{}'", e.getKey());
                        }
                    }
                    return out;
                },
                map -> {
                    Map<String, V> out = new HashMap<>();
                    for (Map.Entry<Integer, V> e : map.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
                    return out;
                });
    }

    public static final Codec<VillagerProfile> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(VillagerProfile::id),
            Codec.STRING.fieldOf("prof").forGetter(VillagerProfile::profession),
            UUIDUtil.CODEC.optionalFieldOf("owner").forGetter(VillagerProfile::owner),
            levelMapCodec(TradeKey.CODEC.listOf())
                    .optionalFieldOf("picks", new HashMap<>()).forGetter(VillagerProfile::picks),
            levelMapCodec(MerchantOffer.CODEC.listOf())
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
        // Picks replace any imported legacy offers for the level.
        legacy.remove(level);
        picks.put(level, List.copyOf(p));
    }

    public void setLegacy(int level, List<MerchantOffer> offers) {
        legacy.put(level, List.copyOf(offers));
    }

    public static VillagerProfile fresh(UUID id, String profession) {
        return new VillagerProfile(id, profession, Optional.empty(), new HashMap<>(), new HashMap<>());
    }

    /** Fresh profile that already records its owner. */
    public static VillagerProfile fresh(UUID id, String profession, UUID owner) {
        return new VillagerProfile(id, profession, Optional.of(owner), new HashMap<>(), new HashMap<>());
    }

    /**
     * Return a copy carrying the given owner, preserving picks/legacy as-is.
     */
    public VillagerProfile withOwner(UUID newOwner) {
        return new VillagerProfile(id, profession, Optional.of(newOwner), picks, legacy);
    }

    /**
     * Return a copy keyed to a new villager UUID. Entity conversions (zombification, curing)
     * spawn a new entity with a fresh UUID, so the profile has to follow it.
     */
    public VillagerProfile withId(UUID newId) {
        return new VillagerProfile(newId, profession, owner, picks, legacy);
    }
}

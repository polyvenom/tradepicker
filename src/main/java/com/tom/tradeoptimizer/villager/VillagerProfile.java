package com.tom.tradeoptimizer.villager;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.tom.tradeoptimizer.trade.TradeKey;
import net.minecraft.core.UUIDUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-villager record of player choices.
 *
 *   id          — the villager's UUID
 *   profession  — registry name string, kept for safety (rebuild from scratch if profession changes)
 *   picks       — map<level, list of TradeKeys the player picked for that level>
 */
public record VillagerProfile(
        UUID id,
        String profession,
        Map<Integer, List<TradeKey>> picks
) {

    public VillagerProfile {
        // Defensive copy — codec may return immutable maps.
        picks = new HashMap<>(picks);
    }

    public static final Codec<VillagerProfile> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(VillagerProfile::id),
            Codec.STRING.fieldOf("prof").forGetter(VillagerProfile::profession),
            Codec.unboundedMap(
                    Codec.STRING.xmap(Integer::parseInt, String::valueOf),
                    TradeKey.CODEC.listOf()
            ).fieldOf("picks").forGetter(VillagerProfile::picks)
    ).apply(inst, VillagerProfile::new));

    /** Has the player picked trades for this level yet? */
    public boolean hasPicksFor(int level) {
        List<TradeKey> p = picks.get(level);
        return p != null && !p.isEmpty();
    }

    public List<TradeKey> picksFor(int level) {
        return picks.getOrDefault(level, List.of());
    }

    public void setPicks(int level, List<TradeKey> p) {
        picks.put(level, List.copyOf(p));
    }

    public void clearAll() {
        picks.clear();
    }

    public static VillagerProfile fresh(UUID id, String profession) {
        return new VillagerProfile(id, profession, new HashMap<>());
    }
}

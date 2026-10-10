package com.tom.tradeoptimizer.villager;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World-saved registry of all villager profiles, so picks survive logout / world reload.
 *
 * Stored on the OVERWORLD's data storage, not the villager's current level: storage is
 * per-dimension but a villager keeps its UUID through a portal, so a per-dimension store would
 * read it as unclaimed on the far side. One global store keyed by UUID follows the villager.
 */
public final class VillagerProfileState extends SavedData {

    private static final String SAVE_ID = TradeOptimizer.MOD_ID + "_villager_profiles";

    /** The full state, serialized as a list of VillagerProfile records. */
    static final Codec<VillagerProfileState> CODEC =
            lenientList().xmap(
                    list -> {
                        VillagerProfileState s = new VillagerProfileState();
                        for (VillagerProfile p : list) s.profiles.put(p.id(), p);
                        return s;
                    },
                    state -> List.copyOf(state.profiles.values())
            );

    /**
     * Decode each profile on its own so one unreadable entry is skipped instead of failing the
     * whole list. Encoding is the plain list codec.
     */
    private static Codec<List<VillagerProfile>> lenientList() {
        return new Codec<>() {
            @Override
            public <T> DataResult<Pair<List<VillagerProfile>, T>> decode(DynamicOps<T> ops, T input) {
                return ops.getList(input).map(entries -> {
                    List<VillagerProfile> out = new ArrayList<>();
                    entries.accept(element -> VillagerProfile.CODEC.parse(ops, element)
                            .resultOrPartial(err -> TradeOptimizer.LOGGER.warn(
                                    "Skipping unreadable villager profile: {}", err))
                            .ifPresent(out::add));
                    return Pair.of(out, input);
                });
            }

            @Override
            public <T> DataResult<T> encode(List<VillagerProfile> input, DynamicOps<T> ops, T prefix) {
                return VillagerProfile.CODEC.listOf().encode(input, ops, prefix);
            }
        };
    }

    // DataFixTypes.LEVEL is the lowest-stakes datafix type available.
    public static final SavedDataType<VillagerProfileState> TYPE = new SavedDataType<>(
            SAVE_ID,
            VillagerProfileState::new,
            CODEC,
            DataFixTypes.LEVEL
    );

    final Map<UUID, VillagerProfile> profiles = new HashMap<>();

    public VillagerProfileState() {}

    public VillagerProfile get(UUID id) {
        return profiles.get(id);
    }

    public void update(VillagerProfile p) {
        profiles.put(p.id(), p);
        setDirty();
    }

    /**
     * Move a profile to another villager UUID: conversions (zombify, cure, lightning) spawn a new
     * entity with a new UUID. Returns true if a profile was moved.
     */
    public boolean rekey(UUID from, UUID to) {
        VillagerProfile p = profiles.remove(from);
        if (p == null) return false;
        profiles.put(to, p.withId(to));
        setDirty();
        return true;
    }

    public static VillagerProfileState get(ServerLevel level) {
        ServerLevel overworld = level.getServer().overworld();
        VillagerProfileState global = overworld.getDataStorage().computeIfAbsent(TYPE);
        if (level != overworld) absorbLegacyDimensionStore(level, global);
        return global;
    }

    /**
     * Fold an old per-dimension store (profiles filed under the dimension the villager was claimed
     * in) into the global one. Emptying the source makes this a no-op after the first call.
     */
    private static void absorbLegacyDimensionStore(ServerLevel level, VillagerProfileState global) {
        VillagerProfileState legacy = level.getDataStorage().get(TYPE);
        if (legacy == null || legacy.profiles.isEmpty()) return;
        int moved = 0;
        for (Map.Entry<UUID, VillagerProfile> e : legacy.profiles.entrySet()) {
            if (global.profiles.putIfAbsent(e.getKey(), e.getValue()) == null) moved++;
        }
        legacy.profiles.clear();
        legacy.setDirty();
        global.setDirty();
        TradeOptimizer.LOGGER.info("Migrated {} villager profile(s) from {} into the global profile store",
                moved, level.dimension().identifier());
    }
}

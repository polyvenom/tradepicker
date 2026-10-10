package com.tom.tradeoptimizer.villager;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
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
 * Stored on the overworld's data storage, not the villager's current level: storage is
 * per-dimension but a villager keeps its UUID through a portal, so a per-dimension store would
 * make it read as unclaimed on the far side. One global store keyed by UUID follows it everywhere.
 */
public final class VillagerProfileState extends SavedData {
    public static final SavedDataType<VillagerProfileState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, "villager_profiles"),
            VillagerProfileState::new,
            lenientList().xmap(
                    VillagerProfileState::fromList,
                    s -> new ArrayList<>(s.profiles.values())),
            null
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

    private final Map<UUID, VillagerProfile> profiles = new HashMap<>();

    public VillagerProfileState() {}

    private static VillagerProfileState fromList(List<VillagerProfile> list) {
        VillagerProfileState state = new VillagerProfileState();
        for (VillagerProfile p : list) state.profiles.put(p.id(), p);
        return state;
    }

    public VillagerProfile get(UUID id) {
        return profiles.get(id);
    }

    public void update(VillagerProfile p) {
        profiles.put(p.id(), p);
        setDirty();
    }

    /**
     * Move a profile from one villager UUID to another (conversions spawn a new entity with a new
     * UUID). Returns true if a profile was moved.
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
     * Fold a pre-1.4.0 per-dimension store into the global one. Emptying the source makes this a
     * no-op after the first call, so it can sit on the hot path.
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

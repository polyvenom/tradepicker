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
 * Stored on the OVERWORLD's data storage rather than the level the villager happens to be
 * standing in. Data storage is per-dimension but a villager keeps its UUID when it goes
 * through a portal, so a per-dimension store made the same villager read as unclaimed on
 * the far side — a second player could claim, re-pick and reset it, and coming back the
 * stale original profile would reassert over the live offers. One global store keyed by
 * UUID makes the profile follow the villager everywhere.
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
     * whole list — a single corrupt profile must not cost every other villager on the server its
     * picks. Encoding is the plain list codec; only the read side is forgiving.
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
     * Move a profile from one villager UUID to another. Conversions (villager →
     * zombie villager, cure back, lightning → witch) never copy the UUID — vanilla
     * spawns a brand-new entity — so the profile must be re-keyed to follow it.
     * Returns true if a profile was moved.
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
     * Fold a pre-1.4.0 per-dimension store into the global one. Worlds that ran an older build
     * have profiles filed under whichever dimension the villager was in when it was claimed;
     * without this they would simply vanish on upgrade. Emptying the source makes this a no-op
     * on every call after the first, so it can sit on the hot path.
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

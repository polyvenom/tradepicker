package com.tom.tradeoptimizer.villager;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

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
 *
 * 1.21.1 uses the NBT-based SavedData API (Factory + save/load overrides) rather than the
 * codec-first SavedDataType of the 26.x line; the profile list is still serialized through
 * VillagerProfile.CODEC, just bridged via RegistryOps (MerchantOffer's codec needs registries).
 */
public final class VillagerProfileState extends SavedData {

    private static final String TAG_PROFILES = "profiles";
    private static final String SAVE_ID = TradeOptimizer.MOD_ID + "_villager_profiles";

    public static final SavedData.Factory<VillagerProfileState> FACTORY = new SavedData.Factory<>(
            VillagerProfileState::new, VillagerProfileState::load, null);

    /**
     * Codec for the full state: serializes as a list of VillagerProfile records. 1.21.1 has no
     * codec-first SavedData API, so load/save below bridge it through RegistryOps by hand.
     */
    public static final Codec<VillagerProfileState> CODEC =
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

    final Map<UUID, VillagerProfile> profiles = new HashMap<>();

    public VillagerProfileState() {}

    private static VillagerProfileState load(CompoundTag tag, HolderLookup.Provider registries) {
        Tag listTag = tag.get(TAG_PROFILES);
        if (listTag == null) return new VillagerProfileState();
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return CODEC.parse(ops, listTag)
                .resultOrPartial(err -> TradeOptimizer.LOGGER.error("Failed to load villager profiles: {}", err))
                .orElseGet(VillagerProfileState::new);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        CODEC.encodeStart(ops, this)
                .resultOrPartial(err -> TradeOptimizer.LOGGER.error("Failed to save villager profiles: {}", err))
                .ifPresent(encoded -> tag.put(TAG_PROFILES, encoded));
        return tag;
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
        VillagerProfileState global = overworld.getDataStorage().computeIfAbsent(FACTORY, SAVE_ID);
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
        VillagerProfileState legacy = level.getDataStorage().get(FACTORY, SAVE_ID);
        if (legacy == null || legacy.profiles.isEmpty()) return;
        int moved = 0;
        for (Map.Entry<UUID, VillagerProfile> e : legacy.profiles.entrySet()) {
            if (global.profiles.putIfAbsent(e.getKey(), e.getValue()) == null) moved++;
        }
        legacy.profiles.clear();
        legacy.setDirty();
        global.setDirty();
        TradeOptimizer.LOGGER.info("Migrated {} villager profile(s) from {} into the global profile store",
                moved, level.dimension().location());
    }
}

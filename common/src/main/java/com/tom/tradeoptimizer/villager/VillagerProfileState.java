package com.tom.tradeoptimizer.villager;

import com.mojang.serialization.Codec;
import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * World-saved registry of all villager profiles. Lives in the ServerLevel's data
 * storage so picks survive logout / world reload.
 *
 * 1.21.5: SavedData.Factory was replaced by SavedDataType; SavedData.save() was removed —
 * persistence is now fully codec-driven. We provide a Codec&lt;VillagerProfileState&gt; backed
 * by VillagerProfile.CODEC.listOf(), serialising the state as a JSON-mapped list.
 *
 * NOTE: existing worlds saved by the 1.21.4 backport used NBT via the old Factory path
 * (save key = "tradeoptimizer_villager_profiles"). The 1.21.5 codec path writes the same
 * key but now serialises as a Mojang Codec list rather than raw CompoundTag. Old saves
 * will not be migrated — players need to re-pick. Acceptable for a branch backport.
 */
public final class VillagerProfileState extends SavedData {

    private static final String SAVE_ID = TradeOptimizer.MOD_ID + "_villager_profiles";

    /**
     * Codec for the full state: serializes as a list of VillagerProfile records.
     * Used by SavedDataType to load/save (1.21.5+ codec-first SavedData API).
     */
    static final Codec<VillagerProfileState> CODEC =
            VillagerProfile.CODEC.listOf().xmap(
                    list -> {
                        VillagerProfileState s = new VillagerProfileState();
                        for (VillagerProfile p : list) s.profiles.put(p.id(), p);
                        return s;
                    },
                    state -> List.copyOf(state.profiles.values())
            );

    // 1.21.5: SavedDataType(String id, Supplier<T>, Codec<T>, DataFixTypes)
    // The Supplier is called for brand-new data (no file on disk yet); the Codec is used to
    // read/write the file. DataFixTypes.LEVEL is the lowest-stakes dfx type available.
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

    public static VillagerProfileState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }
}

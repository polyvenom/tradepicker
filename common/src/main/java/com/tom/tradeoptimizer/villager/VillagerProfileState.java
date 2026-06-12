package com.tom.tradeoptimizer.villager;

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
 * World-saved registry of all villager profiles. Lives in the ServerLevel's data
 * storage so picks survive logout / world reload.
 *
 * 1.21.1 uses the NBT-based SavedData API (Factory + save/load overrides) rather than the
 * codec-first SavedDataType of the 26.x line; the profile list is still serialized through
 * VillagerProfile.CODEC, just bridged via RegistryOps (MerchantOffer's codec needs registries).
 */
public final class VillagerProfileState extends SavedData {

    private static final String TAG_PROFILES = "profiles";

    public static final SavedData.Factory<VillagerProfileState> FACTORY = new SavedData.Factory<>(
            VillagerProfileState::new, VillagerProfileState::load, null);

    private final Map<UUID, VillagerProfile> profiles = new HashMap<>();

    public VillagerProfileState() {}

    private static VillagerProfileState load(CompoundTag tag, HolderLookup.Provider registries) {
        VillagerProfileState state = new VillagerProfileState();
        Tag listTag = tag.get(TAG_PROFILES);
        if (listTag != null) {
            RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
            VillagerProfile.CODEC.listOf().parse(ops, listTag)
                    .resultOrPartial(err -> TradeOptimizer.LOGGER.error("Failed to load villager profiles: {}", err))
                    .ifPresent(list -> {
                        for (VillagerProfile p : list) state.profiles.put(p.id(), p);
                    });
        }
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        RegistryOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        List<VillagerProfile> list = new ArrayList<>(profiles.values());
        VillagerProfile.CODEC.listOf().encodeStart(ops, list)
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

    public static VillagerProfileState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, TradeOptimizer.MOD_ID + "_villager_profiles");
    }
}

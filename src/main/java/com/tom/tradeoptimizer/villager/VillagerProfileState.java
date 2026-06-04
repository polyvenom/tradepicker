package com.tom.tradeoptimizer.villager;

import com.mojang.serialization.Codec;
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
 * World-saved registry of all villager profiles. Lives in the ServerLevel's data
 * storage so picks survive logout / world reload.
 */
public final class VillagerProfileState extends SavedData {
    public static final SavedDataType<VillagerProfileState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, "villager_profiles"),
            VillagerProfileState::new,
            VillagerProfile.CODEC.listOf().xmap(
                    VillagerProfileState::fromList,
                    s -> new ArrayList<>(s.profiles.values())),
            null
    );

    private final Map<UUID, VillagerProfile> profiles = new HashMap<>();

    public VillagerProfileState() {}

    private static VillagerProfileState fromList(List<VillagerProfile> list) {
        VillagerProfileState state = new VillagerProfileState();
        for (VillagerProfile p : list) state.profiles.put(p.id(), p);
        return state;
    }

    public VillagerProfile getOrCreate(UUID id, String profession) {
        VillagerProfile p = profiles.get(id);
        if (p == null || !p.profession().equals(profession)) {
            // Profession changed (e.g. villager got a new job) — wipe the profile.
            p = VillagerProfile.fresh(id, profession);
            profiles.put(id, p);
            setDirty();
        }
        return p;
    }

    public VillagerProfile get(UUID id) {
        return profiles.get(id);
    }

    public void update(VillagerProfile p) {
        profiles.put(p.id(), p);
        setDirty();
    }

    public void remove(UUID id) {
        if (profiles.remove(id) != null) setDirty();
    }

    public static VillagerProfileState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }
}

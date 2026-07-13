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
        return level.getDataStorage().computeIfAbsent(TYPE);
    }
}

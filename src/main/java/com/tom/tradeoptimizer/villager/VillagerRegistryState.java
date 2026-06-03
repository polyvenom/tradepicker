package com.tom.tradeoptimizer.villager;

import com.mojang.serialization.Codec;
import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VillagerRegistryState extends PersistentState {
    private static final String STORAGE_KEY = TradeOptimizer.MOD_ID + "_known_villagers";

    private static final Codec<VillagerRegistryState> CODEC = VillagerEntry.CODEC.listOf()
            .xmap(VillagerRegistryState::fromList, s -> new ArrayList<>(s.villagers.values()));

    public static final PersistentStateType<VillagerRegistryState> TYPE = new PersistentStateType<>(
            STORAGE_KEY,
            VillagerRegistryState::new,
            CODEC,
            null
    );

    private final Map<UUID, VillagerEntry> villagers = new LinkedHashMap<>();

    public VillagerRegistryState() {}

    private static VillagerRegistryState fromList(List<VillagerEntry> list) {
        VillagerRegistryState state = new VillagerRegistryState();
        for (VillagerEntry e : list) state.villagers.put(e.id(), e);
        return state;
    }

    public Collection<VillagerEntry> all() {
        return villagers.values();
    }

    public VillagerEntry get(UUID id) {
        return villagers.get(id);
    }

    public void upsert(VillagerEntry entry) {
        villagers.put(entry.id(), entry);
        markDirty();
    }

    public void forget(UUID id) {
        if (villagers.remove(id) != null) markDirty();
    }

    public static VillagerRegistryState get(ServerWorld world) {
        return world.getPersistentStateManager().getOrCreate(TYPE);
    }
}

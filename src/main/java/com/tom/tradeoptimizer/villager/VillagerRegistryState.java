package com.tom.tradeoptimizer.villager;

import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VillagerRegistryState extends SavedData {
    public static final SavedDataType<VillagerRegistryState> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, "known_villagers"),
            VillagerRegistryState::new,
            VillagerEntry.CODEC.listOf().xmap(VillagerRegistryState::fromList, s -> new ArrayList<>(s.villagers.values())),
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
        setDirty();
    }

    public void forget(UUID id) {
        if (villagers.remove(id) != null) setDirty();
    }

    public static VillagerRegistryState get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }
}
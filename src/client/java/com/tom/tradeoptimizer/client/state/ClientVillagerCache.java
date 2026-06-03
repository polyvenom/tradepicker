package com.tom.tradeoptimizer.client.state;

import com.tom.tradeoptimizer.villager.VillagerEntry;

import java.util.Collections;
import java.util.List;

public final class ClientVillagerCache {
    private ClientVillagerCache() {}

    private static volatile List<VillagerEntry> CURRENT = Collections.emptyList();

    public static void set(List<VillagerEntry> entries) {
        CURRENT = List.copyOf(entries);
    }

    public static List<VillagerEntry> get() {
        return CURRENT;
    }
}

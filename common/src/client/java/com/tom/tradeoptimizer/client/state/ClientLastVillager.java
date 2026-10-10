package com.tom.tradeoptimizer.client.state;

import java.util.UUID;

/**
 * The last merchant the player right-clicked, so MerchantScreenMixin's Reset button knows which
 * villager the open trade screen belongs to and whether it is a villager at all (vanilla's
 * MerchantMenu exposes neither). Wandering Traders share the screen but aren't resettable.
 */
public final class ClientLastVillager {
    private ClientLastVillager() {}

    private static volatile UUID lastUuid;
    private static volatile boolean lastWasVillager;

    /** Record that the player just clicked a real villager. */
    public static void set(UUID id) {
        lastUuid = id;
        lastWasVillager = true;
    }

    /** Record a click on a non-villager (e.g. a Wandering Trader), dropping the stale UUID. */
    public static void clear() {
        lastUuid = null;
        lastWasVillager = false;
    }

    public static UUID get() { return lastUuid; }

    /** True when the most recent merchant the player opened was a real villager. */
    public static boolean wasVillager() { return lastWasVillager; }
}

package com.tom.tradeoptimizer.client.state;

import java.util.UUID;

/**
 * Tracks the last merchant the player right-clicked, so the Reset button in MerchantScreenMixin
 * knows which villager the open trade screen belongs to and whether it is a villager at all
 * (MerchantMenu exposes neither). Wandering Traders share the screen but aren't resettable.
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

    /**
     * Record that the player just clicked something that isn't a villager (e.g. a Wandering
     * Trader), so Reset can't target an earlier villager.
     */
    public static void clear() {
        lastUuid = null;
        lastWasVillager = false;
    }

    public static UUID get() { return lastUuid; }

    /** True when the most recent merchant the player opened was a real villager. */
    public static boolean wasVillager() { return lastWasVillager; }
}

package com.tom.tradeoptimizer.client.state;

import java.util.UUID;

/**
 * Tracks the last merchant the player right-clicked, so the Reset button in
 * MerchantScreenMixin knows (a) which villager the open trade screen belongs to and
 * (b) whether that merchant is even a villager. Vanilla's MerchantMenu doesn't expose
 * either fact directly.
 *
 * Wandering Traders share the same MerchantScreen but aren't resettable by this mod —
 * tracking villager-ness here keeps the Reset button (and the reset packet) from firing
 * on a previously-clicked villager while a Wandering Trader's screen is open.
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
     * Record that the player just clicked something that isn't a villager (e.g. a
     * Wandering Trader). Drops the stale UUID and flag so the Reset button stays hidden
     * and can't reset a villager the player clicked earlier.
     */
    public static void clear() {
        lastUuid = null;
        lastWasVillager = false;
    }

    public static UUID get() { return lastUuid; }

    /** True when the most recent merchant the player opened was a real villager. */
    public static boolean wasVillager() { return lastWasVillager; }
}

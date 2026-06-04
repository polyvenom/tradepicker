package com.tom.tradeoptimizer.client.state;

import java.util.UUID;

/**
 * Tracks the UUID of the last villager the player right-clicked on. The Reset button
 * in MerchantScreenMixin reads this to know which villager the open trade screen
 * belongs to — vanilla's MerchantMenu doesn't expose that information directly.
 */
public final class ClientLastVillager {
    private ClientLastVillager() {}

    private static volatile UUID lastUuid;

    public static void set(UUID id) { lastUuid = id; }
    public static UUID get() { return lastUuid; }
}

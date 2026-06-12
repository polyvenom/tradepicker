package com.tom.tradeoptimizer.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stable extension surface for Trade Picker add-ons.
 *
 * Loader-agnostic by design: plain statics in the shared {@code common} module, no platform or
 * networking types in the signatures, so a Fabric or NeoForge add-on registers the same way. The
 * base mod ships this whether or not any add-on is present; with no handlers registered every method
 * here is inert and the picker behaves exactly as before.
 */
public final class TradePickerApi {
    private TradePickerApi() {}

    private static final List<VillagerInteractionHandler> INTERACTION_HANDLERS = new CopyOnWriteArrayList<>();

    /** Register a handler that may take over villager right-clicks. Call once during mod init. */
    public static void registerInteractionHandler(VillagerInteractionHandler handler) {
        INTERACTION_HANDLERS.add(handler);
    }

    /** The registered handlers, in registration order. Consumed by the base interaction flow. */
    public static List<VillagerInteractionHandler> interactionHandlers() {
        return INTERACTION_HANDLERS;
    }
}

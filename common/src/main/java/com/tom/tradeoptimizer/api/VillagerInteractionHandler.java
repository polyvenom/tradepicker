package com.tom.tradeoptimizer.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;

/**
 * Lets an add-on take over a villager right-click before Trade Picker runs its own picker flow.
 *
 * Register an implementation during mod init via {@link TradePickerApi#registerInteractionHandler}.
 * Handlers are consulted in registration order on every (non-nitwit) villager right-click; the
 * first to return {@link Result#TAKEOVER} wins and the base mod does nothing further.
 *
 * Runs server-side on the main thread, after the villager is confirmed employable and before any
 * of Trade Picker's own networking, so an implementation owns the whole interaction.
 */
@FunctionalInterface
public interface VillagerInteractionHandler {

    Result onInteract(ServerPlayer player, Villager villager);

    enum Result {
        /** This handler claimed the interaction; Trade Picker must not run its picker flow. */
        TAKEOVER,
        /** Not this handler's villager; Trade Picker (or the next handler) proceeds normally. */
        PASS
    }
}

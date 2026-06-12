package com.tom.tradeoptimizer.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Lets an add-on take over a villager right-click before Trade Picker runs its own picker flow.
 * Trade Picker: Mastery uses this to replace the picker with its skill-tree screen for the
 * professions it covers, while leaving every other villager on the normal picker.
 *
 * Register an implementation during your mod's init via
 * {@link TradePickerApi#registerInteractionHandler}. Handlers are consulted in registration order
 * on every (non-nitwit) villager right-click; the first to return {@link Result#TAKEOVER} wins and
 * the base mod does nothing further for that interaction.
 *
 * Runs server-side on the main thread, after Trade Picker has confirmed the villager is employable
 * but before any of its own networking — so an implementation owns the whole interaction.
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

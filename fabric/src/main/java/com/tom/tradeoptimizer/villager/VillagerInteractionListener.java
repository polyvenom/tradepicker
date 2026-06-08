package com.tom.tradeoptimizer.villager;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Hooks the player's right-click on a villager and delegates to ProfileController.
 *
 * Returns SUCCESS when we handled the interaction — either we sent the picker to the
 * client or we opened the merchant menu ourselves. SUCCESS acknowledges the click so
 * the client's prediction queue clears and follow-up right-clicks aren't swallowed.
 * Returns PASS only when we did nothing (nitwit / unemployed villager, a non-villager
 * entity, or a client without the mod), so vanilla's normal mob-interact can run.
 *
 * We deliberately never use SUCCESS_SERVER. An earlier version used it to cancel
 * vanilla, which left Mojang's client-side prediction queue stuck on the entity
 * (subsequent right-clicks were swallowed until the player interacted with a different
 * entity). Because we open the merchant ourselves rather than letting vanilla do it,
 * plain SUCCESS is enough and sidesteps that prediction snag.
 */
public final class VillagerInteractionListener {
    private VillagerInteractionListener() {}

    public static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!(entity instanceof Villager villager)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            boolean weHandled = !ProfileController.onInteract(sp, villager);
            // SUCCESS when we handled it (picker sent OR merchant opened by us): tells
            // the client the interact was acknowledged so its prediction queue clears
            // and subsequent right-clicks aren't swallowed.
            // PASS when we did nothing (e.g. nitwit / no-profession): vanilla can handle.
            return weHandled ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
    }
}

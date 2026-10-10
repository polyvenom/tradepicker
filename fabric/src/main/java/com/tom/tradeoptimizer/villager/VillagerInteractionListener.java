package com.tom.tradeoptimizer.villager;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;

/**
 * Hooks the player's right-click on a villager and delegates to ProfileController.
 *
 * Returns SUCCESS when we handled the interaction (picker sent, or merchant opened by us), which
 * clears the client's prediction queue so follow-up right-clicks aren't swallowed. Returns PASS
 * only when we did nothing (nitwit / unemployed villager, a non-villager, a client without the
 * mod), so vanilla's mob-interact can run.
 *
 * Never SUCCESS_SERVER: it leaves the client's prediction queue stuck on the entity, swallowing
 * right-clicks until the player interacts with another one. We open the merchant ourselves, so
 * plain SUCCESS is enough.
 */
public final class VillagerInteractionListener {
    private VillagerInteractionListener() {}

    public static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!(entity instanceof Villager villager)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            boolean weHandled = !ProfileController.onInteract(sp, villager);
            return weHandled ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
    }
}

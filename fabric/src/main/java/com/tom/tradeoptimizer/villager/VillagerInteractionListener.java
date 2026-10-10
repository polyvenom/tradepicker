package com.tom.tradeoptimizer.villager;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Hooks the player's right-click on a villager and delegates to ProfileController.
 *
 * Returns SUCCESS when we handled the interaction (picker sent or merchant opened by us), and PASS
 * when we did nothing (nitwit / unemployed, non-villager, client without the mod) so vanilla runs.
 *
 * Never use SUCCESS_SERVER: it leaves the client's prediction queue stuck on the entity and
 * swallows later right-clicks. Plain SUCCESS acknowledges the click and is enough, since we open
 * the merchant ourselves.
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

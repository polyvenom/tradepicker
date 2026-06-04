package com.tom.tradeoptimizer.villager;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Hooks the player's right-click on a villager. Delegates the decision (open picker
 * vs. let vanilla merchant proceed) to ProfileController.
 *
 * IMPORTANT: UseEntityCallback fires on BOTH client and server. We only run logic
 * on the server side; the client path returns PASS so vanilla networking proceeds.
 */
public final class VillagerInteractionListener {
    private VillagerInteractionListener() {}

    public static void register() {
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> {
            if (!(entity instanceof Villager villager)) return InteractionResult.PASS;
            if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;

            // Only handle the main-hand interaction so we don't fire twice.
            if (hand != net.minecraft.world.InteractionHand.MAIN_HAND) return InteractionResult.PASS;

            boolean allowVanilla = ProfileController.onInteract(sp, villager);
            return allowVanilla ? InteractionResult.PASS : InteractionResult.SUCCESS_SERVER;
        });
    }
}

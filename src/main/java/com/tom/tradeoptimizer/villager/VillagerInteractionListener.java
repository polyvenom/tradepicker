package com.tom.tradeoptimizer.villager;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Hooks the player's right-click on a villager and delegates to ProfileController.
 *
 * Important: we ALWAYS return InteractionResult.PASS so vanilla's mob interact runs
 * its full course. If the villager has offers (picks already locked in), vanilla
 * opens the merchant menu normally. If the villager has none yet, vanilla's mob
 * interact is a harmless no-op and our picker S2C arrives a tick later to open the
 * picker on top.
 *
 * Earlier versions used SUCCESS / SUCCESS_SERVER to cancel vanilla. That caused
 * Mojang's client-side prediction queue to get stuck on the entity (subsequent
 * right-clicks were swallowed until the player interacted with a different entity
 * to clear the prediction). Returning PASS avoids the prediction system entirely.
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

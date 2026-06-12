package com.tom.tradeoptimizer.neoforge;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.neoforge.network.NeoForgeNetwork;
import com.tom.tradeoptimizer.villager.ProfileController;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * NeoForge entry point. Registers NeoForge-specific networking and events, then runs the shared
 * loader-agnostic init. The picker logic itself lives in :common.
 */
@Mod("tradeoptimizer")
public final class TradeOptimizerNeoForge {

    public TradeOptimizerNeoForge(IEventBus modBus, Dist dist) {
        // Payload types register on the mod bus (RegisterPayloadHandlersEvent).
        modBus.addListener(NeoForgeNetwork::register);

        // Server-side villager interaction on the game bus.
        NeoForge.EVENT_BUS.addListener(TradeOptimizerNeoForge::onEntityInteract);

        // Client-only setup (last-villager tracking). Guarded so a dedicated server never
        // classloads client code.
        if (dist == Dist.CLIENT) {
            com.tom.tradeoptimizer.neoforge.client.NeoForgeClientHandlers.initClient();
        }

        TradeOptimizer.init();
    }

    /**
     * Mirror of the Fabric UseEntityCallback: when we handle the interaction (picker sent or
     * merchant opened by us) we cancel vanilla with a SUCCESS result; otherwise we leave the
     * event alone so vanilla trades normally.
     */
    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getTarget() instanceof Villager villager)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        boolean weHandled = !ProfileController.onInteract(player, villager);
        if (weHandled) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }
}

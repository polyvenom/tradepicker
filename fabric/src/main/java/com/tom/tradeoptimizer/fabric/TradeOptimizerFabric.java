package com.tom.tradeoptimizer.fabric;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.fabric.platform.FabricNetwork;
import com.tom.tradeoptimizer.network.ServerNetworkHandler;
import com.tom.tradeoptimizer.villager.ProfileController;
import com.tom.tradeoptimizer.villager.VillagerInteractionListener;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;

/**
 * Fabric entry point. Registers Fabric-specific networking and events, then runs the shared
 * loader-agnostic init. The picker logic itself lives in :common.
 */
public final class TradeOptimizerFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        // Register payload types (incl. the registerLarge picker channel) before any receivers.
        FabricNetwork.registerPayloadTypes();
        ServerNetworkHandler.register();
        VillagerInteractionListener.register();

        // Conversions (zombify / cure / lightning) replace the entity and mint a new
        // UUID; re-key the profile so picks and ownership survive the round trip.
        ServerLivingEntityEvents.MOB_CONVERSION.register((previous, converted, params) ->
                ProfileController.onMobConverted(previous, converted));

        TradeOptimizer.init();
    }
}

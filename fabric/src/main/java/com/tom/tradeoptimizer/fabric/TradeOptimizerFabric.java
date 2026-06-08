package com.tom.tradeoptimizer.fabric;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.fabric.platform.FabricNetwork;
import com.tom.tradeoptimizer.network.ServerNetworkHandler;
import com.tom.tradeoptimizer.villager.VillagerInteractionListener;
import net.fabricmc.api.ModInitializer;

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

        TradeOptimizer.init();
    }
}

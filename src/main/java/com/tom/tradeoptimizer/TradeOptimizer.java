package com.tom.tradeoptimizer;

import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.network.ServerNetworkHandler;
import com.tom.tradeoptimizer.trade.CycleController;
import com.tom.tradeoptimizer.villager.VillagerInteractionListener;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TradeOptimizer implements ModInitializer {
    public static final String MOD_ID = "tradeoptimizer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        NetworkPayloads.registerCommon();
        ServerNetworkHandler.register();
        VillagerInteractionListener.register();
        CycleController.register();

        // Friendly notice if mrbysco's trade-cycling mod is also installed — both can
        // technically coexist, but our targeted cycling supersedes their manual flow.
        if (FabricLoader.getInstance().isModLoaded("trade-cycling")) {
            LOGGER.warn("trade-cycling mod is installed alongside Trade Optimizer. "
                    + "Recommend removing trade-cycling — our auto-cycle covers the same workstation flow "
                    + "and adds target-trade detection plus best-price tracking.");
        }

        LOGGER.info("Trade Optimizer initialized");
    }
}

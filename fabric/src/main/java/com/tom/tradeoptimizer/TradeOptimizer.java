package com.tom.tradeoptimizer;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.network.ServerNetworkHandler;
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
        TradeOptimizerConfig.get(); // load (and create on first run) config/tradeoptimizer.json
        NetworkPayloads.registerCommon();
        ServerNetworkHandler.register();
        VillagerInteractionListener.register();

        if (FabricLoader.getInstance().isModLoaded("trade-cycling")) {
            LOGGER.warn("trade-cycling mod is installed alongside Trade Optimizer. "
                    + "Recommend removing trade-cycling — this mod replaces its purpose by letting "
                    + "the player pick trades directly instead of cycling for them.");
        }

        LOGGER.info("Trade Optimizer initialized");
    }
}

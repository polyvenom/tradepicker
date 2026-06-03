package com.tom.tradeoptimizer;

import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.network.ServerNetworkHandler;
import com.tom.tradeoptimizer.trade.CycleController;
import com.tom.tradeoptimizer.villager.VillagerInteractionListener;
import net.fabricmc.api.ModInitializer;
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
        LOGGER.info("Trade Optimizer ready");
    }
}

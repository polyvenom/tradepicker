package com.tom.tradeoptimizer.client;

import com.tom.tradeoptimizer.client.keybind.TradeOptimizerKeybinds;
import com.tom.tradeoptimizer.client.net.ClientNetworkHandler;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import net.fabricmc.api.ClientModInitializer;

public final class TradeOptimizerClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        NetworkPayloads.registerClient();
        ClientNetworkHandler.register();
        TradeOptimizerKeybinds.register();
    }
}
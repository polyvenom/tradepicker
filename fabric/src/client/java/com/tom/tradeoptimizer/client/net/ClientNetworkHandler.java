package com.tom.tradeoptimizer.client.net;

import com.tom.tradeoptimizer.client.ui.TradePickerScreen;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientNetworkHandler {
    private ClientNetworkHandler() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(NetworkPayloads.OPEN_PICKER_TYPE, (payload, context) ->
                context.client().execute(() ->
                        context.client().setScreenAndShow(new TradePickerScreen(payload))));
    }
}

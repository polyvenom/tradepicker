package com.tom.tradeoptimizer.client.net;

import com.tom.tradeoptimizer.client.ui.TradePickerScreen;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Opens the picker when the server says to.
 *
 * Use gui.setScreen, not Minecraft.setScreenAndShow: the latter forces an extra renderFrame(false)
 * with the world skipped, which flashes the picker over a black background.
 */
public final class ClientNetworkHandler {
    private ClientNetworkHandler() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(NetworkPayloads.OPEN_PICKER_TYPE, (payload, context) ->
                context.client().execute(() ->
                        context.client().gui.setScreen(new TradePickerScreen(payload))));
    }
}

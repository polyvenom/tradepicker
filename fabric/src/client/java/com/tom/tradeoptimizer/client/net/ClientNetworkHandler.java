package com.tom.tradeoptimizer.client.net;

import com.tom.tradeoptimizer.client.ui.TradePickerScreen;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/**
 * Opens the picker when the server says to.
 *
 * Deliberately gui.setScreen, NOT Minecraft.setScreenAndShow: the latter is setScreen plus a
 * forced renderFrame(false), an immediate extra frame drawn with the world skipped. The picker
 * therefore appeared for one frame over a black background before the next normal frame put the
 * world back — the flash players reported on open.
 */
public final class ClientNetworkHandler {
    private ClientNetworkHandler() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(NetworkPayloads.OPEN_PICKER_TYPE, (payload, context) ->
                context.client().execute(() ->
                        context.client().gui.setScreen(new TradePickerScreen(payload))));
    }
}

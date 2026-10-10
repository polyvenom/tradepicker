package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.villager.ProfileController;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class ServerNetworkHandler {
    private ServerNetworkHandler() {}

    public static void register() {
        // Handlers already run on the main server thread. Don't wrap in server.execute: deferring a
        // tick lets a fast re-click trigger another picker before this one applies.
        ServerPlayNetworking.registerGlobalReceiver(NetworkPayloads.PICKER_SUBMIT_TYPE, (payload, context) ->
                ProfileController.onPickerSubmit(context.player(),
                        payload.villagerId(), payload.level(), payload.picks()));

        ServerPlayNetworking.registerGlobalReceiver(NetworkPayloads.RESET_VILLAGER_TYPE, (payload, context) ->
                ProfileController.onReset(context.player(), payload.villagerId()));
    }
}

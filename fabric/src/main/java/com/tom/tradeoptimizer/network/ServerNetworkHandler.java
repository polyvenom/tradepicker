package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.villager.ProfileController;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class ServerNetworkHandler {
    private ServerNetworkHandler() {}

    public static void register() {
        // Handlers run on the main server thread, so entity state is mutated directly. Don't wrap
        // in server.execute: deferring a tick lets a fast re-click open another picker first.
        ServerPlayNetworking.registerGlobalReceiver(NetworkPayloads.PICKER_SUBMIT_TYPE, (payload, context) ->
                ProfileController.onPickerSubmit(context.player(),
                        payload.villagerId(), payload.level(), payload.picks()));

        ServerPlayNetworking.registerGlobalReceiver(NetworkPayloads.RESET_VILLAGER_TYPE, (payload, context) ->
                ProfileController.onReset(context.player(), payload.villagerId()));
    }
}

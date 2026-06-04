package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.villager.ProfileController;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class ServerNetworkHandler {
    private ServerNetworkHandler() {}

    public static void register() {
        // Fabric's networking-api 6.x runs custom-payload handlers on the main server
        // thread (vanilla calls ensureRunningOnSameThread before dispatching), so we
        // can mutate entity state directly. No need to wrap in server.execute — doing
        // so just defers work to the next tick and opens a race window where a fast
        // re-click triggers another picker before this one applies.
        ServerPlayNetworking.registerGlobalReceiver(NetworkPayloads.PICKER_SUBMIT_TYPE, (payload, context) ->
                ProfileController.onPickerSubmit(context.player(),
                        payload.villagerId(), payload.level(), payload.picks()));

        ServerPlayNetworking.registerGlobalReceiver(NetworkPayloads.RESET_VILLAGER_TYPE, (payload, context) ->
                ProfileController.onReset(context.player(), payload.villagerId()));
    }
}

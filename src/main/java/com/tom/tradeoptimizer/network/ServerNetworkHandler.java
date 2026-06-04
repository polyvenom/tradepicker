package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.villager.ProfileController;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class ServerNetworkHandler {
    private ServerNetworkHandler() {}

    public static void register() {
        // Both handlers fire on the network thread. Anything that mutates entity state
        // (setOffers, setVillagerData, setVillagerXp) MUST run on the server's main tick
        // thread or the writes race with vanilla reads and silently get lost — which
        // looks to the player like "right-click does nothing until I touch a different
        // villager first."
        ServerPlayNetworking.registerGlobalReceiver(NetworkPayloads.PICKER_SUBMIT_TYPE, (payload, context) ->
                context.server().execute(() ->
                        ProfileController.onPickerSubmit(context.player(),
                                payload.villagerId(), payload.level(), payload.picks())));

        ServerPlayNetworking.registerGlobalReceiver(NetworkPayloads.RESET_VILLAGER_TYPE, (payload, context) ->
                context.server().execute(() ->
                        ProfileController.onReset(context.player(), payload.villagerId())));
    }
}

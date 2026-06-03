package com.tom.tradeoptimizer.client.net;

import com.tom.tradeoptimizer.client.state.ClientTradeState;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientNetworkHandler {
    private ClientNetworkHandler() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(NetworkPayloads.SNAPSHOT_TYPE, (payload, context) ->
                context.client().execute(() -> ClientTradeState.setSnapshot(payload.villager())));

        ClientPlayNetworking.registerGlobalReceiver(NetworkPayloads.CYCLE_STATUS_TYPE, (payload, context) ->
                context.client().execute(() -> ClientTradeState.setCycleStatus(payload)));
    }
}

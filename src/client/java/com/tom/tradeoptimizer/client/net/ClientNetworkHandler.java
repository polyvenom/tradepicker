package com.tom.tradeoptimizer.client.net;

import com.tom.tradeoptimizer.client.state.ClientVillagerCache;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientNetworkHandler {
    private ClientNetworkHandler() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(NetworkPayloads.SYNC_ID, (payload, context) -> {
            context.client().execute(() -> ClientVillagerCache.set(payload.villagers()));
        });
    }
}
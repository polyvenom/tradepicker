package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.trade.CycleController;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class ServerNetworkHandler {
    private ServerNetworkHandler() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(NetworkPayloads.CYCLE_ID, (payload, context) -> {
            CycleController.handleRequest(context.player(), payload.villagerId(), payload.workstation());
        });
    }
}

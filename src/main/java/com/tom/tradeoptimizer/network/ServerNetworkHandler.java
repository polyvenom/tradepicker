package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.trade.CycleController;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

public final class ServerNetworkHandler {
    private ServerNetworkHandler() {}

    public static void register() {
        ServerPlayNetworking.registerGlobalReceiver(NetworkPayloads.START_CYCLE_TYPE, (payload, context) ->
                CycleController.startSession(context.player(), payload.villagerId(),
                        payload.workstation(), payload.target()));

        ServerPlayNetworking.registerGlobalReceiver(NetworkPayloads.STOP_CYCLE_TYPE, (payload, context) ->
                CycleController.stopSession(context.player(), "Cancelled by player."));
    }
}

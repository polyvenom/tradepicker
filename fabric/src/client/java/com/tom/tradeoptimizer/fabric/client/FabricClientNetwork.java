package com.tom.tradeoptimizer.fabric.client;

import com.tom.tradeoptimizer.client.platform.IClientNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Fabric implementation of the client-send seam, discovered via META-INF/services. */
public final class FabricClientNetwork implements IClientNetwork {

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPlayNetworking.send(payload);
    }
}

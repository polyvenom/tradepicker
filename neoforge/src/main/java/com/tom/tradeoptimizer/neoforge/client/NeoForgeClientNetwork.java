package com.tom.tradeoptimizer.neoforge.client;

import com.tom.tradeoptimizer.client.platform.IClientNetwork;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** NeoForge implementation of the client-send seam, discovered via META-INF/services. */
public final class NeoForgeClientNetwork implements IClientNetwork {

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        ClientPacketDistributor.sendToServer(payload);
    }
}

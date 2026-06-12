package com.tom.tradeoptimizer.neoforge.client;

import com.tom.tradeoptimizer.client.platform.IClientNetwork;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/** NeoForge implementation of the client-send seam, discovered via META-INF/services. */
public final class NeoForgeClientNetwork implements IClientNetwork {

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        // 21.1 sends C2S through PacketDistributor (the client.network.ClientPacketDistributor
        // class only exists on the 26.x line).
        PacketDistributor.sendToServer(payload);
    }
}

package com.tom.tradeoptimizer.neoforge.client;

import com.tom.tradeoptimizer.client.platform.IClientNetwork;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;

/** NeoForge implementation of the client-send seam, discovered via META-INF/services. */
public final class NeoForgeClientNetwork implements IClientNetwork {

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        // C2S goes through PacketDistributor on this line (ClientPacketDistributor is 26.x only).
        PacketDistributor.sendToServer(payload);
    }
}

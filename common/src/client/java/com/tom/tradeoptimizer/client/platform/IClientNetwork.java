package com.tom.tradeoptimizer.client.platform;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client→server send hook the shared picker UI needs (submit picks, reset villager). */
public interface IClientNetwork {
    void sendToServer(CustomPacketPayload payload);
}

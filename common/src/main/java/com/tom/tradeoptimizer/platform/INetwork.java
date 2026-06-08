package com.tom.tradeoptimizer.platform;

import com.tom.tradeoptimizer.network.OpenPickerS2C;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server→client send hooks the shared {@code ProfileController} needs. Only the send side is
 * abstracted; payload-type registration and receiver wiring live in each loader module (their
 * timing and APIs differ — Fabric registers at init, NeoForge in a registration event).
 */
public interface INetwork {

    /** True if the player's client declared it can receive the picker payload (mod installed). */
    boolean canSendOpenPicker(ServerPlayer player);

    /** Send the picker payload to the player's client. Fabric uses its registerLarge channel. */
    void sendOpenPicker(ServerPlayer player, OpenPickerS2C payload);
}

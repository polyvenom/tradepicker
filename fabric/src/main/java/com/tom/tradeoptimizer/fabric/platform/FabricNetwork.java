package com.tom.tradeoptimizer.fabric.platform;

import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.network.OpenPickerS2C;
import com.tom.tradeoptimizer.network.PickerSubmitC2S;
import com.tom.tradeoptimizer.network.ResetVillagerC2S;
import com.tom.tradeoptimizer.platform.INetwork;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

/** Fabric implementation of the server-send seam, plus payload-type registration. */
public final class FabricNetwork implements INetwork {

    /**
     * Register the custom-payload types. OpenPickerS2C uses registerLarge with a 2 MiB cap because
     * a librarian's full enchant×level option list blows the default 32 KB limit; the tiny C2S
     * packets use the standard register. Called once from the mod entry point.
     */
    public static void registerPayloadTypes() {
        PayloadTypeRegistry.clientboundPlay().registerLarge(
                NetworkPayloads.OPEN_PICKER_TYPE, OpenPickerS2C.STREAM_CODEC, 2 * 1024 * 1024);
        PayloadTypeRegistry.serverboundPlay().register(
                NetworkPayloads.PICKER_SUBMIT_TYPE, PickerSubmitC2S.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(
                NetworkPayloads.RESET_VILLAGER_TYPE, ResetVillagerC2S.STREAM_CODEC);
    }

    @Override
    public boolean canSendOpenPicker(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, NetworkPayloads.OPEN_PICKER_TYPE);
    }

    @Override
    public void sendOpenPicker(ServerPlayer player, OpenPickerS2C payload) {
        ServerPlayNetworking.send(player, payload);
    }
}

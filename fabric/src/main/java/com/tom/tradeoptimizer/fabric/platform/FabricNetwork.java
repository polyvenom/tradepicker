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
     * Register the custom-payload types. Vanilla's 1 MiB clientbound payload cap comfortably fits
     * the librarian's full option list (~30 KB), so no registerLarge is needed.
     */
    public static void registerPayloadTypes() {
        PayloadTypeRegistry.playS2C().register(
                NetworkPayloads.OPEN_PICKER_TYPE, OpenPickerS2C.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(
                NetworkPayloads.PICKER_SUBMIT_TYPE, PickerSubmitC2S.STREAM_CODEC);
        PayloadTypeRegistry.playC2S().register(
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

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
     * Register the custom-payload types. On 1.21.1 the vanilla clientbound custom-payload cap is
     * 1 MiB, which comfortably fits the librarian's full enchant×level option list (~30 KB) — so
     * the 26.x registerLarge carve-out isn't needed here, and 1.21.1's Fabric API names the
     * registries playS2C/playC2S. Called once from the mod entry point.
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

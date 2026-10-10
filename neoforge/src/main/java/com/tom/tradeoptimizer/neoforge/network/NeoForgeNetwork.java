package com.tom.tradeoptimizer.neoforge.network;

import com.tom.tradeoptimizer.neoforge.client.NeoForgeClientHandlers;
import com.tom.tradeoptimizer.network.NetworkPayloads;
import com.tom.tradeoptimizer.network.OpenPickerS2C;
import com.tom.tradeoptimizer.network.PickerSubmitC2S;
import com.tom.tradeoptimizer.network.ResetVillagerC2S;
import com.tom.tradeoptimizer.platform.INetwork;
import com.tom.tradeoptimizer.villager.ProfileController;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * NeoForge networking: payload registration (mod bus) plus the server-send seam.
 *
 * No large-payload registration (Fabric needs registerLarge): NeoForge's GenericPacketSplitter
 * splits oversized packets between NeoForge peers. Payloads are registered optional() so a client
 * without the mod can still connect; the canSendOpenPicker gate then suppresses the picker.
 *
 * Play-phase handlers run on the main thread, so they mutate game state directly.
 */
public final class NeoForgeNetwork implements INetwork {

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(NetworkPayloads.OPEN_PICKER_TYPE, OpenPickerS2C.STREAM_CODEC,
                NeoForgeNetwork::handleOpenPicker);
        registrar.playToServer(NetworkPayloads.PICKER_SUBMIT_TYPE, PickerSubmitC2S.STREAM_CODEC,
                NeoForgeNetwork::handlePickerSubmit);
        registrar.playToServer(NetworkPayloads.RESET_VILLAGER_TYPE, ResetVillagerC2S.STREAM_CODEC,
                NeoForgeNetwork::handleReset);
    }

    // Clientbound: opens the picker. The client-only body is in NeoForgeClientHandlers, which a
    // dedicated server never classloads.
    private static void handleOpenPicker(OpenPickerS2C payload, IPayloadContext context) {
        NeoForgeClientHandlers.openPicker(payload);
    }

    private static void handlePickerSubmit(PickerSubmitC2S payload, IPayloadContext context) {
        ProfileController.onPickerSubmit((ServerPlayer) context.player(),
                payload.villagerId(), payload.level(), payload.picks());
    }

    private static void handleReset(ResetVillagerC2S payload, IPayloadContext context) {
        ProfileController.onReset((ServerPlayer) context.player(), payload.villagerId());
    }

    @Override
    public boolean canSendOpenPicker(ServerPlayer player) {
        return player.connection.hasChannel(NetworkPayloads.OPEN_PICKER_TYPE);
    }

    @Override
    public void sendOpenPicker(ServerPlayer player, OpenPickerS2C payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }
}

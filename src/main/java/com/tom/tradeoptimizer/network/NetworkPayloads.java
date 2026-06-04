package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.TradeOptimizer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class NetworkPayloads {
    private NetworkPayloads() {}

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, path);
    }

    public static final CustomPacketPayload.Type<OpenPickerS2C> OPEN_PICKER_TYPE =
            new CustomPacketPayload.Type<>(id("open_picker"));

    public static final CustomPacketPayload.Type<PickerSubmitC2S> PICKER_SUBMIT_TYPE =
            new CustomPacketPayload.Type<>(id("picker_submit"));

    public static final CustomPacketPayload.Type<ResetVillagerC2S> RESET_VILLAGER_TYPE =
            new CustomPacketPayload.Type<>(id("reset_villager"));

    public static void registerCommon() {
        PayloadTypeRegistry.clientboundPlay().register(OPEN_PICKER_TYPE, OpenPickerS2C.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(PICKER_SUBMIT_TYPE, PickerSubmitC2S.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(RESET_VILLAGER_TYPE, ResetVillagerC2S.STREAM_CODEC);
    }
}

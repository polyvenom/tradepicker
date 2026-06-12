package com.tom.tradeoptimizer.network;

import com.tom.tradeoptimizer.TradeOptimizer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The custom-payload type handles, shared by every loader. These are plain vanilla
 * {@link CustomPacketPayload.Type} constants — registration (and Fabric's registerLarge cap for
 * the picker payload) happens per loader in that loader's networking implementation.
 */
public final class NetworkPayloads {
    private NetworkPayloads() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(TradeOptimizer.MOD_ID, path);
    }

    public static final CustomPacketPayload.Type<OpenPickerS2C> OPEN_PICKER_TYPE =
            new CustomPacketPayload.Type<>(id("open_picker"));

    public static final CustomPacketPayload.Type<PickerSubmitC2S> PICKER_SUBMIT_TYPE =
            new CustomPacketPayload.Type<>(id("picker_submit"));

    public static final CustomPacketPayload.Type<ResetVillagerC2S> RESET_VILLAGER_TYPE =
            new CustomPacketPayload.Type<>(id("reset_villager"));
}

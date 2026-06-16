package com.tom.tradeoptimizer.neoforge.client;

import com.tom.tradeoptimizer.client.state.ClientLastVillager;
import com.tom.tradeoptimizer.client.ui.TradePickerScreen;
import com.tom.tradeoptimizer.network.OpenPickerS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.npc.villager.Villager;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Client-only NeoForge hooks. Loaded lazily (only from the Dist.CLIENT branch of the mod
 * constructor / from the clientbound payload handler), so a dedicated server never touches it.
 */
public final class NeoForgeClientHandlers {
    private NeoForgeClientHandlers() {}

    /** Register client-side event listeners (last-villager tracking for the Reset button). */
    public static void initClient() {
        NeoForge.EVENT_BUS.addListener((PlayerInteractEvent.EntityInteract event) -> {
            if (!event.getLevel().isClientSide()) return;
            if (event.getTarget() instanceof Villager v) {
                ClientLastVillager.set(v.getUUID());
            } else {
                // Wandering Trader / anything else: clear so the Reset button can't target a
                // villager the player clicked earlier.
                ClientLastVillager.clear();
            }
        });
    }

    /** Clientbound OPEN_PICKER handler — opens the picker screen. */
    public static void openPicker(OpenPickerS2C payload) {
        Minecraft.getInstance().setScreenAndShow(new TradePickerScreen(payload));
    }
}

package com.tom.tradeoptimizer.client.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import com.tom.tradeoptimizer.client.ui.TradingOptimizerScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.lwjgl.glfw.GLFW;

public final class TradeOptimizerKeybinds {
    private TradeOptimizerKeybinds() {}

    private static boolean wasPressed = false;

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.screen != null) return;
            
            // 26.1.2 passes the encapsulated Window object directly, rather than a raw long pointer.
            boolean isPressed = InputConstants.isKeyDown(client.getWindow(), GLFW.GLFW_KEY_V);
            
            if (isPressed && !wasPressed) {
                client.setScreen(new TradingOptimizerScreen());
            }
            wasPressed = isPressed;
        });
    }
}
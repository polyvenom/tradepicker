package com.tom.tradeoptimizer.client.keybind;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.client.ui.TradingOptimizerScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class TradeOptimizerKeybinds {
    private TradeOptimizerKeybinds() {}

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of(TradeOptimizer.MOD_ID, "main"));

    public static KeyBinding openBrowser;

    public static void register() {
        openBrowser = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tradeoptimizer.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openBrowser.wasPressed()) {
                MinecraftClient.getInstance().setScreen(new TradingOptimizerScreen());
            }
        });
    }
}

package com.tom.tradeoptimizer.client.keybind;

import com.mojang.blaze3d.platform.InputConstants;
import com.tom.tradeoptimizer.TradeOptimizer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * All keybinds registered through Fabric's KeyMappingHelper so users can rebind them
 * in vanilla Controls. None of these binds default to vanilla-conflicting keys.
 */
public final class TradeOptimizerKeybinds {
    private TradeOptimizerKeybinds() {}

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath(TradeOptimizer.MOD_ID, "main"));

    public static KeyMapping cycleForSelected;
    public static KeyMapping cancelCycle;

    public static void register() {
        cycleForSelected = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.tradeoptimizer.cycle_for_selected",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Y,
                CATEGORY
        ));
        cancelCycle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.tradeoptimizer.cancel_cycle",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,
                CATEGORY
        ));
    }
}

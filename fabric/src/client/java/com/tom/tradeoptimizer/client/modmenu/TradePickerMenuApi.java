package com.tom.tradeoptimizer.client.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.tom.tradeoptimizer.client.ui.TradePickerConfigScreen;

/**
 * ModMenu entry point. Exposes the Trade Picker config screen when the user
 * clicks "Config" on the mod's tile in ModMenu.
 *
 * Wired in fabric.mod.json under entrypoints.modmenu. Fabric only resolves
 * entrypoint classes when their target entrypoint is requested, so this class
 * is dead weight when ModMenu is absent — harmless.
 */
public class TradePickerMenuApi implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TradePickerConfigScreen::new;
    }
}

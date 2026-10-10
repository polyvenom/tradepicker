package com.tom.tradeoptimizer.client.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.tom.tradeoptimizer.client.ui.TradePickerConfigScreen;

/**
 * ModMenu entry point (fabric.mod.json, entrypoints.modmenu): exposes the config screen. Fabric
 * only loads it when ModMenu asks, so it is inert without ModMenu.
 */
public class TradePickerMenuApi implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TradePickerConfigScreen::new;
    }
}

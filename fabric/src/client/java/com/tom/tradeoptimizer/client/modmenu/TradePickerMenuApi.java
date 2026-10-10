package com.tom.tradeoptimizer.client.modmenu;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.tom.tradeoptimizer.client.ui.TradePickerConfigScreen;

/**
 * ModMenu entry point, wired in fabric.mod.json under entrypoints.modmenu. Fabric only loads it
 * when ModMenu requests the entrypoint, so it is harmless without ModMenu.
 */
public class TradePickerMenuApi implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return TradePickerConfigScreen::new;
    }
}

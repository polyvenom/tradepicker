package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Config screen for Trade Picker. Reachable from ModMenu (if installed).
 *
 * Note: the underlying config is server-authoritative (prices are computed
 * server-side). On singleplayer or a locally hosted server this screen edits
 * the config in place. On a remote dedicated server, the operator must edit
 * config/tradeoptimizer.json on the server directly.
 */
public class TradePickerConfigScreen extends Screen {

    private static final int CHECKBOX_W = 300;
    private static final int BUTTON_W   = 150;
    private static final int BUTTON_H   = 20;

    private final Screen parent;

    public TradePickerConfigScreen(Screen parent) {
        super(Component.translatable("tradeoptimizer.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int leftX   = centerX - CHECKBOX_W / 2;

        this.addRenderableWidget(new StringWidget(
                leftX, 20, CHECKBOX_W, 20, this.getTitle(), this.font));

        this.addRenderableWidget(Checkbox.builder(
                        Component.translatable("tradeoptimizer.config.vanillaPricing"), this.font)
                .pos(leftX, 55)
                .selected(TradeOptimizerConfig.get().vanillaPricing())
                .tooltip(Tooltip.create(Component.translatable("tradeoptimizer.config.vanillaPricing.tip")))
                .onValueChange((cb, v) -> TradeOptimizerConfig.get().setVanillaPricing(v))
                .build());

        this.addRenderableWidget(Button.builder(
                        Component.translatable("tradeoptimizer.config.done"),
                        btn -> this.onClose())
                .bounds(centerX - BUTTON_W / 2, this.height - 30, BUTTON_W, BUTTON_H)
                .build());
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(this.parent);
    }
}

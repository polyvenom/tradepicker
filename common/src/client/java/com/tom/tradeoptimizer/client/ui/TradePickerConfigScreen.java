package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.config.TradeOptimizerConfig.GearEnchantMode;
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

    /** Professions whose enchanted-gear / tipped-arrow trades support per-profession cost scaling. */
    private static final String[] ENCHANT_PROFESSIONS = {
            "minecraft:armorer", "minecraft:weaponsmith", "minecraft:toolsmith",
            "minecraft:fletcher", "minecraft:fisherman"
    };

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int leftX   = centerX - CHECKBOX_W / 2;

        this.addRenderableWidget(new StringWidget(
                leftX, 12, CHECKBOX_W, 20, this.getTitle(), this.font));

        this.addRenderableWidget(Checkbox.builder(
                        Component.translatable("tradeoptimizer.config.vanillaPricing"), this.font)
                .pos(leftX, 38)
                .selected(TradeOptimizerConfig.get().vanillaPricing())
                .tooltip(Tooltip.create(Component.translatable("tradeoptimizer.config.vanillaPricing.tip")))
                .onValueChange((cb, v) -> TradeOptimizerConfig.get().setVanillaPricing(v))
                .build());

        this.addRenderableWidget(Checkbox.builder(
                        Component.translatable("tradeoptimizer.config.vanillaBookLimits"), this.font)
                .pos(leftX, 60)
                .selected(TradeOptimizerConfig.get().vanillaBookLimits())
                .tooltip(Tooltip.create(Component.translatable("tradeoptimizer.config.vanillaBookLimits.tip")))
                .onValueChange((cb, v) -> TradeOptimizerConfig.get().setVanillaBookLimits(v))
                .build());

        // Gear-enchant mode: cycles Single <-> Headline. (Combo builder ships in a later update.)
        this.addRenderableWidget(Button.builder(gearModeLabel(), btn -> {
                    GearEnchantMode next = TradeOptimizerConfig.get().gearEnchantMode() == GearEnchantMode.SINGLE
                            ? GearEnchantMode.HEADLINE : GearEnchantMode.SINGLE;
                    TradeOptimizerConfig.get().setGearEnchantMode(next);
                    btn.setMessage(gearModeLabel());
                })
                .bounds(leftX, 84, CHECKBOX_W, BUTTON_H)
                .tooltip(Tooltip.create(Component.translatable("tradeoptimizer.config.gearMode.tip")))
                .build());

        // Per-profession cost scaling for picked enchanted trades.
        this.addRenderableWidget(new StringWidget(
                leftX, 112, CHECKBOX_W, 12,
                Component.translatable("tradeoptimizer.config.costScaling"), this.font));
        int colW = CHECKBOX_W / 2;
        for (int i = 0; i < ENCHANT_PROFESSIONS.length; i++) {
            String profId = ENCHANT_PROFESSIONS[i];
            String profPath = profId.substring(profId.indexOf(':') + 1);
            int col = i % 2;
            int row = i / 2;
            int x = leftX + col * colW;
            int y = 128 + row * 22;
            this.addRenderableWidget(Checkbox.builder(
                            Component.translatable("entity.minecraft.villager." + profPath), this.font)
                    .pos(x, y)
                    .selected(TradeOptimizerConfig.get().isCostScaling(profId))
                    .tooltip(Tooltip.create(Component.translatable("tradeoptimizer.config.costScaling.tip")))
                    .onValueChange((cb, v) -> TradeOptimizerConfig.get().setCostScaling(profId, v))
                    .build());
        }

        this.addRenderableWidget(Button.builder(
                        Component.translatable("tradeoptimizer.config.done"),
                        btn -> this.onClose())
                .bounds(centerX - BUTTON_W / 2, this.height - 28, BUTTON_W, BUTTON_H)
                .build());
    }

    private static Component gearModeLabel() {
        String key = TradeOptimizerConfig.get().gearEnchantMode() == GearEnchantMode.SINGLE
                ? "tradeoptimizer.config.gearMode.single"
                : "tradeoptimizer.config.gearMode.headline";
        return Component.translatable("tradeoptimizer.config.gearMode", Component.translatable(key));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) this.minecraft.setScreen(this.parent);
    }
}

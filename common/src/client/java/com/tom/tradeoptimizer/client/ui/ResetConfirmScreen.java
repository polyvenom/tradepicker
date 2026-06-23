package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.client.platform.ClientServices;
import com.tom.tradeoptimizer.network.ResetVillagerC2S;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.UUID;

/**
 * "Are you sure?" gate for the Reset button — wipes the villager's locked-in trades
 * and drops them back to Novice. Sends ResetVillagerC2S on confirm.
 */
public final class ResetConfirmScreen extends Screen {

    private final UUID villagerId;
    private final Screen back;

    public ResetConfirmScreen(UUID villagerId, Screen back) {
        super(Component.literal("Reset Villager Trades?"));
        this.villagerId = villagerId;
        this.back = back;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("Reset"), b -> doReset())
                .bounds(this.width / 2 - 84, this.height / 2 + 10, 80, 20)
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> minecraft.setScreen(back))
                .bounds(this.width / 2 + 4, this.height / 2 + 10, 80, 20)
                .build());
    }

    private void doReset() {
        ClientServices.NETWORK.sendToServer(new ResetVillagerC2S(villagerId));
        // Close back to the world — server will message the player.
        minecraft.setScreen(null);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial) {
        super.extractRenderState(g, mouseX, mouseY, partial);
        String l1 = "This villager will lose all locked-in trades";
        String l2 = "and drop back to Novice (zero XP).";
        String l3 = "Any future levels also need to be re-picked.";
        g.text(this.font, l1, this.width / 2 - this.font.width(l1) / 2, this.height / 2 - 30, 0xFFFFFFFF);
        g.text(this.font, l2, this.width / 2 - this.font.width(l2) / 2, this.height / 2 - 18, 0xFFFFFFFF);
        g.text(this.font, l3, this.width / 2 - this.font.width(l3) / 2, this.height / 2 - 6, 0xFFAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

package com.tom.tradeoptimizer.client.mixin;

import com.tom.tradeoptimizer.client.state.ClientLastVillager;
import com.tom.tradeoptimizer.client.ui.ResetConfirmScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * Adds a small "Reset" button to the merchant trade screen. Clicking it opens our
 * ResetConfirmScreen which double-checks the player wants to wipe the villager.
 *
 * Extending AbstractContainerScreen<MerchantMenu> is the standard mixin trick to get
 * @Shadow access to inherited fields (leftPos/topPos/imageWidth) — the constructor
 * is dead code at runtime, only present so javac is happy with the extends clause.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin extends AbstractContainerScreen<MerchantMenu> {

    private MerchantScreenMixin(MerchantMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void tradeoptimizer$addResetButton(CallbackInfo ci) {
        // Place a "Reset" button at the top-right corner just above the trade window.
        int btnW = 50;
        int btnH = 12;
        int x = this.leftPos + this.imageWidth - btnW - 4;
        int y = this.topPos - btnH - 2;
        this.addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
            UUID id = ClientLastVillager.get();
            if (id == null) return;
            Screen self = (MerchantScreen) (Object) this;
            this.minecraft.setScreen(new ResetConfirmScreen(id, self));
        }).bounds(x, y, btnW, btnH).build());
    }
}

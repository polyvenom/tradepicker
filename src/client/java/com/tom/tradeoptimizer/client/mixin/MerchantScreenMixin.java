package com.tom.tradeoptimizer.client.mixin;

import com.tom.tradeoptimizer.client.ui.MerchantOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MerchantMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws our trade-rating overlay on top of the vanilla merchant screen.
 *
 * Extending AbstractContainerScreen<MerchantMenu> here is the standard mixin trick to
 * give @Shadow access to inherited fields like leftPos/topPos. The constructor is for
 * javac only — mixin replaces this class at load time, so super() is never called.
 *
 * Also exposes private `shopItem` via MerchantScreenAccessor so the cycle keybind
 * knows which trade row the player highlighted.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin extends AbstractContainerScreen<MerchantMenu> implements MerchantScreenAccessor {

    @Shadow private int scrollOff;
    @Shadow private int shopItem;

    private MerchantScreenMixin(MerchantMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
    }

    @Inject(method = "extractContents", at = @At("TAIL"))
    private void tradeoptimizer$drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                            float partialTick, CallbackInfo ci) {
        MerchantScreen self = (MerchantScreen) (Object) this;
        MerchantOverlay.render(self, this.leftPos, this.topPos, this.scrollOff, g, mouseX, mouseY, self.getFont());
    }

    @Override
    public int tradeoptimizer$getShopItem() {
        return this.shopItem;
    }
}

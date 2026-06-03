package com.tom.tradeoptimizer.client.mixin;

import com.tom.tradeoptimizer.client.ui.MerchantOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws our trade-rating overlay on top of the vanilla merchant screen.
 *
 * Hooks `extractContents` at TAIL — that's the render pass for the screen body in 26.1.2's
 * "extract render state then draw" model. We add our chips + tooltips by drawing to the
 * GuiGraphicsExtractor after vanilla has finished its own content draws.
 */
@Mixin(MerchantScreen.class)
public abstract class MerchantScreenMixin {

    @Shadow protected int leftPos;
    @Shadow protected int topPos;
    @Shadow private int scrollOff;

    @Inject(method = "extractContents", at = @At("TAIL"))
    private void tradeoptimizer$drawOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY,
                                            float partialTick, CallbackInfo ci) {
        MerchantScreen self = (MerchantScreen) (Object) this;
        MerchantOverlay.render(self, this.leftPos, this.topPos, this.scrollOff, g, mouseX, mouseY, self.getFont());
    }
}

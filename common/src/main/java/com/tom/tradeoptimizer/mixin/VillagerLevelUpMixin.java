package com.tom.tradeoptimizer.mixin;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.villager.VillagerProfileState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Holds a managed villager at its current rank until the player has picked the new level's trades.
 *
 * Vanilla's increaseMerchantCareer bumps the level, then rolls two random trades. Both are wrong
 * here: the trades aren't the player's choice, and a rank without picks has missing trades.
 * Cancelling the whole method (not just the roll) also stops levels stacking while the player trades.
 *
 * To the player this looks like vanilla's pending level-up: XP accrues, the bar sits full, the
 * rank waits until they close the screen. Coming back opens the picker, and ProfileController
 * (which re-checks vanilla's shouldIncreaseLevel condition) applies the rank with the picks.
 *
 * Only villagers with a profile are held; untouched villagers level normally, and vanilla's
 * other updateTrades caller (first profession assignment) is left alone, since the fresh-import
 * path reads its result.
 */
@Mixin(Villager.class)
public abstract class VillagerLevelUpMixin {

    @Inject(method = "increaseMerchantCareer", at = @At("HEAD"), cancellable = true)
    private void tradeoptimizer$holdLevelUntilPicked(ServerLevel level, CallbackInfo ci) {
        Villager self = (Villager) (Object) this;
        if (VillagerProfileState.get(level).get(self.getUUID()) == null) return;
        TradeOptimizer.LOGGER.debug("[levelup] holding {} at level {} until the player picks",
                self.getUUID(), self.getVillagerData().level());
        ci.cancel();
    }
}

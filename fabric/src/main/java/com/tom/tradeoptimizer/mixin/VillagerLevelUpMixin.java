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
 * Vanilla's increaseMerchantCareer bumps the level, then rolls two random trades for it. Both are
 * wrong here: the trades aren't the player's choice, and a rank without picks has missing trades.
 * Cancelling the whole method (not just the roll) also stops levels stacking while the player trades.
 *
 * The player sees vanilla's pending-level-up behaviour (XP accrues, the bar sits full, the rank
 * waits for the screen to close), except coming back opens the picker and the rank arrives with
 * the chosen trades. ProfileController re-checks vanilla's shouldIncreaseLevel condition on
 * interact and applies the level-up itself once the picks land.
 *
 * Only villagers with a profile are held; untouched villagers and the import path level up
 * normally, and vanilla's other updateTrades caller (first profession assignment) is left alone.
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

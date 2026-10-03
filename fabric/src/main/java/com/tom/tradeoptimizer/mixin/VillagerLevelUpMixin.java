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
 * Vanilla's increaseMerchantCareer is two lines: bump the level, then roll two random trades for
 * it. Both are wrong here — the trades aren't the player's choice, and a rank the player hasn't
 * picked for is a rank with missing trades. Cancelling the whole method instead of just the trade
 * roll also removes the window where levels could stack up while the player traded.
 *
 * What the player sees matches vanilla's own pending-level-up behaviour: XP keeps accruing, the
 * bar sits full, and the rank doesn't move until they close the screen and come back — except now
 * coming back opens the picker, and the rank arrives together with the trades they chose.
 * ProfileController re-checks vanilla's own shouldIncreaseLevel condition on interact and applies
 * the level-up itself once the picks land.
 *
 * Only villagers with a profile are held. One the mod has never touched levels up normally, so
 * vanilla villagers and the "import an existing villager" path are untouched — and this leaves
 * vanilla's other caller of updateTrades (first profession assignment) alone, which is what the
 * truly-fresh import path reads to decide whether a villager has been traded with.
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

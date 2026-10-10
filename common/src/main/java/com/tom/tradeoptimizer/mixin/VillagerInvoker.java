package com.tom.tradeoptimizer.mixin;

import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes vanilla's private {@code Villager.updateSpecialPrices(Player)} so the picker
 * flow can apply reputation / Hero-of-the-Village discounts.
 *
 * Vanilla only calls updateSpecialPrices from its private {@code startTrading(Player)}, which
 * this mod bypasses (it opens the merchant via setTradingPlayer + openTradingScreen), so
 * ProfileController calls this invoker to apply the discounts itself.
 */
@Mixin(Villager.class)
public interface VillagerInvoker {
    @Invoker("updateSpecialPrices")
    void tradeoptimizer$updateSpecialPrices(Player player);
}

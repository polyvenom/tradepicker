package com.tom.tradeoptimizer.mixin;

import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes vanilla's private {@code Villager.updateSpecialPrices(Player)} so the picker
 * flow can apply reputation / Hero-of-the-Village discounts.
 *
 * Vanilla only calls updateSpecialPrices from its private {@code startTrading(Player)}
 * method. This mod deliberately bypasses startTrading (it opens the merchant manually
 * via setTradingPlayer + openTradingScreen to avoid the 1-frame menu-disappear bug), so
 * the discount step was getting skipped. ProfileController calls this invoker to put it
 * back, exactly mirroring vanilla's startTrading order.
 */
@Mixin(Villager.class)
public interface VillagerInvoker {
    @Invoker("updateSpecialPrices")
    void tradeoptimizer$updateSpecialPrices(Player player);
}

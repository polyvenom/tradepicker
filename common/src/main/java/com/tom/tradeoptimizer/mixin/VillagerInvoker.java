package com.tom.tradeoptimizer.mixin;

import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes vanilla's private {@code Villager.updateSpecialPrices(Player)}. Vanilla only calls it
 * from {@code startTrading}, which the mod bypasses by opening the merchant manually, so
 * ProfileController calls it to apply reputation / Hero-of-the-Village discounts.
 */
@Mixin(Villager.class)
public interface VillagerInvoker {
    @Invoker("updateSpecialPrices")
    void tradeoptimizer$updateSpecialPrices(Player player);
}

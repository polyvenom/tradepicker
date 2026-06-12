package com.tom.tradeoptimizer.mixin;

import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes vanilla's private {@code Villager.updateSpecialPrices(Player)} so the picker
 * flow can apply reputation / Hero-of-the-Village discounts. NeoForge counterpart of the
 * Fabric mixin — identical because both run on Mojmapped Minecraft.
 */
@Mixin(Villager.class)
public interface VillagerInvoker {
    @Invoker("updateSpecialPrices")
    void tradeoptimizer$updateSpecialPrices(Player player);
}

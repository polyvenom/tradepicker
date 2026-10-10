package com.tom.tradeoptimizer.mixin;

import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Setter for {@code AbstractVillager.offers}: {@code overrideOffers} is an empty no-op stub, so
 * the picker writes the protected field directly.
 */
@Mixin(AbstractVillager.class)
public interface AbstractVillagerAccessor {
    @Accessor("offers")
    void tradeoptimizer$setOffers(MerchantOffers offers);
}

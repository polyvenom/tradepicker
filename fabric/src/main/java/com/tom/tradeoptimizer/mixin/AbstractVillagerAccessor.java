package com.tom.tradeoptimizer.mixin;

import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.trading.MerchantOffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Setter for {@code AbstractVillager.offers}. 1.21.1 exposes no working public setter —
 * {@code overrideOffers} exists but is an empty no-op stub — so the picker writes the
 * protected field directly when applying the player's chosen trades.
 */
@Mixin(AbstractVillager.class)
public interface AbstractVillagerAccessor {
    @Accessor("offers")
    void tradeoptimizer$setOffers(MerchantOffers offers);
}

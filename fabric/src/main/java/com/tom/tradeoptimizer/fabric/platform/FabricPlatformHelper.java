package com.tom.tradeoptimizer.fabric.platform;

import com.tom.tradeoptimizer.mixin.AbstractVillagerAccessor;
import com.tom.tradeoptimizer.mixin.VillagerInvoker;
import com.tom.tradeoptimizer.platform.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffers;

import java.nio.file.Path;

/** Fabric implementation of the platform seam, discovered via META-INF/services. */
public final class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public void updateVillagerSpecialPrices(Villager villager, ServerPlayer player) {
        // Fabric exposes vanilla's private updateSpecialPrices via the @Invoker mixin.
        ((VillagerInvoker) villager).tradeoptimizer$updateSpecialPrices(player);
    }

    @Override
    public void setVillagerOffers(Villager villager, MerchantOffers offers) {
        ((AbstractVillagerAccessor) villager).tradeoptimizer$setOffers(offers);
    }
}

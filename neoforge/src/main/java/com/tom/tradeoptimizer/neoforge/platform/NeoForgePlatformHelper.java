package com.tom.tradeoptimizer.neoforge.platform;

import com.tom.tradeoptimizer.mixin.AbstractVillagerAccessor;
import com.tom.tradeoptimizer.mixin.VillagerInvoker;
import com.tom.tradeoptimizer.platform.IPlatformHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffers;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;

/** NeoForge implementation of the platform seam, discovered via META-INF/services. */
public final class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public void updateVillagerSpecialPrices(Villager villager, ServerPlayer player) {
        // Same @Invoker mixin approach as Fabric (NeoForge also runs SpongePowered Mixin).
        ((VillagerInvoker) villager).tradeoptimizer$updateSpecialPrices(player);
    }

    @Override
    public void setVillagerOffers(Villager villager, MerchantOffers offers) {
        ((AbstractVillagerAccessor) villager).tradeoptimizer$setOffers(offers);
    }
}

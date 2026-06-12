package com.tom.tradeoptimizer.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.trading.MerchantOffers;

import java.nio.file.Path;

/**
 * Loader-specific environment and game hooks that the shared code needs. One implementation
 * per loader, discovered via {@link Services} using {@link java.util.ServiceLoader} — no
 * Architectury runtime dependency.
 */
public interface IPlatformHelper {

    /** The config directory ({@code …/config}). Fabric: FabricLoader; NeoForge: FMLPaths.CONFIGDIR. */
    Path getConfigDir();

    /** Whether another mod is present. Fabric: FabricLoader.isModLoaded; NeoForge: ModList. */
    boolean isModLoaded(String modId);

    /**
     * Re-apply reputation / Hero-of-the-Village discounts by invoking vanilla's private
     * {@code Villager.updateSpecialPrices(player)}. Fabric does this through an @Invoker mixin;
     * NeoForge through an access transformer. Kept behind the seam so the shared code carries
     * no mixin dependency.
     */
    void updateVillagerSpecialPrices(Villager villager, ServerPlayer player);

    /**
     * Replace the villager's live offer list. 1.21.1 has NO public setter: the vanilla
     * {@code AbstractVillager.overrideOffers} is an empty no-op stub there (verified from the
     * mapped bytecode — silently does nothing), so each loader writes the protected
     * {@code offers} field directly via an @Accessor mixin.
     */
    void setVillagerOffers(Villager villager, MerchantOffers offers);
}

package com.tom.tradeoptimizer.platform;

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
     * Replace the villager's live offer list. Vanilla's {@code AbstractVillager.overrideOffers} is
     * an empty no-op stub, so each loader writes the protected {@code offers} field via an
     * @Accessor mixin.
     */
    void setVillagerOffers(Villager villager, MerchantOffers offers);
}

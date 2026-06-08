package com.tom.tradeoptimizer.platform;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

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
}

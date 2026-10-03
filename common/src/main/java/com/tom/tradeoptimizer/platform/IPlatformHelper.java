package com.tom.tradeoptimizer.platform;

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
}

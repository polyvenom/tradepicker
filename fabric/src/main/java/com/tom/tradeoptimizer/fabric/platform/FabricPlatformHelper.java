package com.tom.tradeoptimizer.fabric.platform;

import com.tom.tradeoptimizer.platform.IPlatformHelper;
import net.fabricmc.loader.api.FabricLoader;

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
}

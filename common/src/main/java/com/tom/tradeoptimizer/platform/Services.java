package com.tom.tradeoptimizer.platform;

import java.util.ServiceLoader;

/**
 * ServiceLoader bootstrap for the platform implementations. Each loader module ships a
 * {@code META-INF/services} file naming its {@link IPlatformHelper} / {@link INetwork}
 * implementation; this resolves them once at class-load time.
 */
public final class Services {
    private Services() {}

    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);
    public static final INetwork NETWORK = load(INetwork.class);

    private static <T> T load(Class<T> clazz) {
        return ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No platform implementation found for " + clazz.getName()));
    }
}

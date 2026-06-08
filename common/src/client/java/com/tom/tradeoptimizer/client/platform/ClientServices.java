package com.tom.tradeoptimizer.client.platform;

import java.util.ServiceLoader;

/**
 * ServiceLoader bootstrap for the client-side platform implementation. Resolved only on the
 * client, from the loader's client source set; never touched on a dedicated server.
 */
public final class ClientServices {
    private ClientServices() {}

    public static final IClientNetwork NETWORK = load(IClientNetwork.class);

    private static <T> T load(Class<T> clazz) {
        return ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No client platform implementation found for " + clazz.getName()));
    }
}

package com.tom.tradeoptimizer;

import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import com.tom.tradeoptimizer.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loader-agnostic constants and shared initialization. Each loader's entry point registers its
 * own networking and events, then calls {@link #init()} for the loader-independent setup.
 *
 * The mod id and Java package stay "tradeoptimizer" across every loader so existing worlds'
 * villager picks (keyed by this id in SavedData) persist unchanged.
 */
public final class TradeOptimizer {
    private TradeOptimizer() {}

    public static final String MOD_ID = "tradeoptimizer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** Shared init: load config, and nudge if the now-redundant trade-cycling mod is present. */
    public static void init() {
        TradeOptimizerConfig.get(); // load (and create on first run) config/tradeoptimizer.json

        if (Services.PLATFORM.isModLoaded("trade-cycling")) {
            LOGGER.warn("trade-cycling mod is installed alongside Trade Optimizer. "
                    + "Recommend removing trade-cycling — this mod replaces its purpose by letting "
                    + "the player pick trades directly instead of cycling for them.");
        }

        LOGGER.info("Trade Optimizer initialized");
    }
}

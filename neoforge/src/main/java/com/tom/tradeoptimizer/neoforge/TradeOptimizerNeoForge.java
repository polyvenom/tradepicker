package com.tom.tradeoptimizer.neoforge;

import net.neoforged.fml.common.Mod;

/**
 * NeoForge entry point. Currently an empty skeleton (Step 2c) — it only proves the
 * NeoForge API resolves and this module assembles as a valid mod.
 *
 * Step 5 will flesh this out: call the shared TradeOptimizerCommon.init(), register the
 * NeoForge networking (default 1 MiB PayloadRegistrar) and the EntityInteract event,
 * and provide the ServiceLoader platform implementations.
 */
@Mod("tradeoptimizer")
public final class TradeOptimizerNeoForge {
    public TradeOptimizerNeoForge() {
    }
}

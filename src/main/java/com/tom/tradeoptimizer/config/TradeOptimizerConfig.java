package com.tom.tradeoptimizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tom.tradeoptimizer.TradeOptimizer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TradeOptimizerConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Master switch for the auto-cycle feature. Default off for safety on shared servers. */
    public boolean cyclingEnabled = false;
    /** Ticks between break and place phases of a cycle. 5 = quarter second. */
    public int cycleCooldownTicks = 5;
    /** Ticks to wait after placing the workstation before reading new offers (villager AI re-roll). */
    public int postPlaceWaitTicks = 40;
    /** Hard cap on the tracked-villager index per world. */
    public int maxKnownVillagers = 512;
    /** Only allow operators to start a cycle session. */
    public boolean requireOpToCycle = true;
    /** Max number of automatic re-cycles before giving up the target search. */
    public int maxCycleAttempts = 200;
    /** Show rating chips in the merchant screen. */
    public boolean showMerchantOverlay = true;
    /** Show tooltips with best-price + baseline info on merchant trade hover. */
    public boolean showMerchantTooltips = true;

    private static TradeOptimizerConfig INSTANCE;

    public static TradeOptimizerConfig get() {
        if (INSTANCE == null) INSTANCE = load();
        return INSTANCE;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(TradeOptimizer.MOD_ID + ".json");
    }

    private static TradeOptimizerConfig load() {
        Path p = path();
        if (!Files.exists(p)) {
            TradeOptimizerConfig fresh = new TradeOptimizerConfig();
            save(fresh);
            return fresh;
        }
        try {
            String json = Files.readString(p);
            TradeOptimizerConfig cfg = GSON.fromJson(json, TradeOptimizerConfig.class);
            return cfg != null ? cfg : new TradeOptimizerConfig();
        } catch (IOException e) {
            TradeOptimizer.LOGGER.warn("Failed to read config, using defaults", e);
            return new TradeOptimizerConfig();
        }
    }

    public static void save(TradeOptimizerConfig cfg) {
        try {
            Files.writeString(path(), GSON.toJson(cfg));
        } catch (IOException e) {
            TradeOptimizer.LOGGER.warn("Failed to write config", e);
        }
    }
}

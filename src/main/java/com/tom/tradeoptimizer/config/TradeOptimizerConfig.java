package com.tom.tradeoptimizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tom.tradeoptimizer.TradeOptimizer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server-side configuration for Trade Picker.
 *
 * Lives at {@code config/tradeoptimizer.json}. Pricing is computed server-side, so this is
 * the authoritative home for the price-mode switch: in singleplayer it is effectively the
 * player's own toggle; on a dedicated server only the operator's setting governs the prices
 * every client sees.
 *
 * The file is created with defaults on first load and re-serialized afterward so new keys
 * are filled in automatically across updates.
 */
public final class TradeOptimizerConfig {
    private static final String FILE_NAME = TradeOptimizer.MOD_ID + ".json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static TradeOptimizerConfig instance;

    /**
     * {@code false} (default): the picker shows and applies the lowest possible vanilla price
     * for every trade — the mod's signature behavior. {@code true}: prices use vanilla's
     * randomized range, but seeded deterministically per villager + trade so the previewed
     * price matches the applied price and can't be re-rolled by reopening the picker.
     */
    private boolean vanillaPricing = false;

    public boolean vanillaPricing() {
        return vanillaPricing;
    }

    public void setVanillaPricing(boolean value) {
        this.vanillaPricing = value;
        save();
    }

    public static TradeOptimizerConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static TradeOptimizerConfig load() {
        Path p = path();
        TradeOptimizerConfig cfg = null;
        if (Files.exists(p)) {
            try {
                cfg = GSON.fromJson(Files.readString(p), TradeOptimizerConfig.class);
            } catch (Exception e) {
                TradeOptimizer.LOGGER.warn("Failed to read {} — using defaults: {}", FILE_NAME, e.getMessage());
            }
        }
        if (cfg == null) cfg = new TradeOptimizerConfig();
        cfg.save(); // create the file with defaults, or normalize it with any newly added keys
        return cfg;
    }

    private void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            TradeOptimizer.LOGGER.warn("Failed to write {}: {}", FILE_NAME, e.getMessage());
        }
    }
}

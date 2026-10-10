package com.tom.tradeoptimizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.platform.Services;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Server-side configuration for Trade Picker.
 *
 * Lives at {@code config/tradeoptimizer.json}. Pricing is computed server-side, so on a dedicated
 * server only the operator's setting governs the prices every client sees. The file is
 * re-serialized on load so new keys are filled in.
 */
public final class TradeOptimizerConfig {
    private static final String FILE_NAME = TradeOptimizer.MOD_ID + ".json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // volatile + double-checked locking in get(): server and render threads can both hit first load.
    private static volatile TradeOptimizerConfig instance;

    /** Bumped by every setter so caches elsewhere can tell when the config changed. */
    private static volatile int revision;

    public static int revision() {
        return revision;
    }

    /**
     * {@code false} (default): the lowest possible vanilla price for every trade. {@code true}:
     * vanilla's randomized range, seeded per villager + trade so the previewed price matches the
     * applied one and can't be re-rolled by reopening the picker.
     */
    private volatile boolean vanillaPricing = false;

    public boolean vanillaPricing() {
        return vanillaPricing;
    }

    public void setVanillaPricing(boolean value) {
        this.vanillaPricing = value;
        revision++;
    }

    /**
     * {@code false} (default): up to {@code picksRequired} enchanted books at every level.
     * {@code true}: cap book picks per level at how many book trades vanilla offers there
     * (usually one).
     */
    private volatile boolean vanillaBookLimits = false;

    public boolean vanillaBookLimits() {
        return vanillaBookLimits;
    }

    public void setVanillaBookLimits(boolean value) {
        this.vanillaBookLimits = value;
        revision++;
    }

    /**
     * {@code false} (default): trades already on this villager still appear in the picker, marked.
     * {@code true}: they are removed (#7), unless that would leave fewer cards than the level
     * requires.
     */
    private volatile boolean hidePickedTrades = false;

    public boolean hidePickedTrades() {
        return hidePickedTrades;
    }

    public void setHidePickedTrades(boolean value) {
        this.hidePickedTrades = value;
        revision++;
    }

    /**
     * How enchanted gear trades (anything that isn't an enchanted book) become pickable cards.
     *
     * <ul>
     *   <li>{@link GearEnchantMode#SINGLE} — one card per (enchantment × level).</li>
     *   <li>{@link GearEnchantMode#HEADLINE} (default) — one card per enchantment; the game still
     *       rolls its level AND any vanilla bonus enchantments.</li>
     * </ul>
     * {@link #parse} falls back to HEADLINE for any other string.
     */
    public enum GearEnchantMode {
        SINGLE, HEADLINE;

        public static GearEnchantMode parse(String s) {
            if (s == null) return HEADLINE;
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return HEADLINE;
            }
        }
    }

    /** Stored as a string so the JSON stays human-editable ("single" / "headline"). */
    private volatile String gearEnchantMode = GearEnchantMode.HEADLINE.name().toLowerCase(Locale.ROOT);

    public GearEnchantMode gearEnchantMode() {
        return GearEnchantMode.parse(gearEnchantMode);
    }

    public void setGearEnchantMode(GearEnchantMode mode) {
        this.gearEnchantMode = mode.name().toLowerCase(Locale.ROOT);
        revision++;
    }

    /**
     * Professions (full id, e.g. {@code minecraft:weaponsmith}) whose enchanted-gear / tipped-arrow
     * trades cost more for rarer, higher-level enchantments. Empty by default.
     */
    private volatile Set<String> costScalingProfessions = new TreeSet<>();

    public boolean isCostScaling(String professionId) {
        return costScalingProfessions != null && costScalingProfessions.contains(professionId);
    }

    public void setCostScaling(String professionId, boolean value) {
        // Copy-on-write: other threads read this set, so never mutate a published one.
        Set<String> next = new TreeSet<>();
        if (costScalingProfessions != null) next.addAll(costScalingProfessions);
        if (value) next.add(professionId);
        else next.remove(professionId);
        costScalingProfessions = next;
        revision++;
    }

    public static TradeOptimizerConfig get() {
        TradeOptimizerConfig c = instance;
        if (c == null) {
            synchronized (TradeOptimizerConfig.class) {
                c = instance;
                if (c == null) instance = c = load();
            }
        }
        return c;
    }

    private static Path path() {
        return Services.PLATFORM.getConfigDir().resolve(FILE_NAME);
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
        // Gson hands back a LinkedHashSet or null.
        cfg.costScalingProfessions = cfg.costScalingProfessions == null
                ? new TreeSet<>() : new TreeSet<>(cfg.costScalingProfessions);
        cfg.save();
        return cfg;
    }

    /** Writes the config file. Setters don't call this; the config screen saves once on close. */
    public void save() {
        try {
            Files.writeString(path(), GSON.toJson(this));
        } catch (IOException e) {
            TradeOptimizer.LOGGER.warn("Failed to write {}: {}", FILE_NAME, e.getMessage());
        }
    }
}

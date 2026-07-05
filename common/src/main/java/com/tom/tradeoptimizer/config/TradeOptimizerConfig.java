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

    /**
     * {@code false} (default): the picker lets you choose up to {@code picksRequired} enchanted
     * books at every level — the mod's generous default. {@code true}: cap the number of book
     * picks per level to how many book trades vanilla actually offers there (usually one), forcing
     * the remaining pick(s) onto non-book trades for a vanilla-accurate librarian economy.
     */
    private boolean vanillaBookLimits = false;

    public boolean vanillaBookLimits() {
        return vanillaBookLimits;
    }

    public void setVanillaBookLimits(boolean value) {
        this.vanillaBookLimits = value;
        save();
    }

    /**
     * {@code false} (default): trades already on this villager (picked at an earlier level) still
     * appear in the picker, marked with a check so you can tell them apart. {@code true}: they are
     * removed from the picker list entirely — you only ever see trades you don't have yet
     * (issue #7). The filter never shrinks the list below the number of picks the level requires,
     * so a level can always be filled.
     */
    private boolean hidePickedTrades = false;

    public boolean hidePickedTrades() {
        return hidePickedTrades;
    }

    public void setHidePickedTrades(boolean value) {
        this.hidePickedTrades = value;
        save();
    }

    /**
     * How enchanted GEAR trades (sword, bow, armor, tools, fishing rod — anything that isn't an
     * enchanted book) are turned into pickable cards. Books are unaffected; they always expand into
     * one card per (enchantment × level).
     *
     * <ul>
     *   <li>{@link GearEnchantMode#SINGLE} — one card per (enchantment × level). Pick "Looting III"
     *       and get exactly that one enchantment, nothing else.</li>
     *   <li>{@link GearEnchantMode#HEADLINE} (default) — one card per enchantment. You choose the
     *       headline enchantment; the game still rolls its level AND any vanilla bonus enchantments,
     *       so the result keeps vanilla's surprise + balance.</li>
     *   <li>{@link GearEnchantMode#COMBO} — reserved for the upcoming combo-builder UI; treated as
     *       {@link GearEnchantMode#HEADLINE} until that ships so saves written now stay valid.</li>
     * </ul>
     */
    public enum GearEnchantMode {
        SINGLE, HEADLINE, COMBO;

        public static GearEnchantMode parse(String s) {
            if (s == null) return HEADLINE;
            try {
                return valueOf(s.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return HEADLINE;
            }
        }
    }

    /** Stored as a string so the JSON stays human-editable ("single" / "headline" / "combo"). */
    private String gearEnchantMode = GearEnchantMode.HEADLINE.name().toLowerCase(Locale.ROOT);

    public GearEnchantMode gearEnchantMode() {
        return GearEnchantMode.parse(gearEnchantMode);
    }

    public void setGearEnchantMode(GearEnchantMode mode) {
        this.gearEnchantMode = mode.name().toLowerCase(Locale.ROOT);
        save();
    }

    /**
     * Professions (by full id, e.g. {@code minecraft:weaponsmith}) for which picked enchanted-gear /
     * tipped-arrow trades cost MORE for rarer + higher-level enchantments, instead of the mod's flat
     * cheapest price. Empty by default — every profession just follows {@link #vanillaPricing}.
     *
     * A {@link TreeSet} so the serialized JSON array is stable/sorted (nicer diffs, deterministic).
     */
    private Set<String> costScalingProfessions = new TreeSet<>();

    public boolean isCostScaling(String professionId) {
        return costScalingProfessions != null && costScalingProfessions.contains(professionId);
    }

    public void setCostScaling(String professionId, boolean value) {
        if (costScalingProfessions == null) costScalingProfessions = new TreeSet<>();
        if (value) costScalingProfessions.add(professionId);
        else costScalingProfessions.remove(professionId);
        save();
    }

    public static TradeOptimizerConfig get() {
        if (instance == null) instance = load();
        return instance;
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

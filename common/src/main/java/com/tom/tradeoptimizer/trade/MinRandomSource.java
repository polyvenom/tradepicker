package com.tom.tradeoptimizer.trade;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A deterministic-zero RandomSource — every random call returns the minimum value.
 *
 * Plugged into the LootContext when we ask a VillagerTrade to generate a MerchantOffer.
 * Because Mojang's NumberProviders implement min..max as `min + nextInt(max - min + 1)`,
 * a nextInt-returns-0 source produces the minimum (cheapest) price end.
 *
 * Side effects we accept: if a trade uses random for choosing variants (e.g. dye colors,
 * unbiased pools), it will get the first item. For the picker UI, each VillagerTrade is
 * already a separate selectable entry, so variant-roll is not a concern.
 */
public final class MinRandomSource implements RandomSource {
    public static final MinRandomSource INSTANCE = new MinRandomSource();

    private final RandomSource fallback = RandomSource.create(0L);

    private MinRandomSource() {}

    @Override public RandomSource fork() { return this; }
    @Override public PositionalRandomFactory forkPositional() { return fallback.forkPositional(); }
    @Override public void setSeed(long seed) {}
    @Override public int nextInt() { return 0; }
    @Override public int nextInt(int bound) { return 0; }
    @Override public long nextLong() { return 0L; }
    @Override public boolean nextBoolean() { return false; }
    @Override public float nextFloat() { return 0f; }
    @Override public double nextDouble() { return 0d; }
    @Override public double nextGaussian() { return 0d; }
}

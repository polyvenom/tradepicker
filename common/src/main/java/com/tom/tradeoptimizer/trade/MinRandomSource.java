package com.tom.tradeoptimizer.trade;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A RandomSource whose every call returns the minimum value.
 *
 * Used in the LootContext when generating a MerchantOffer: Mojang's NumberProviders implement
 * min..max as {@code min + nextInt(max - min + 1)}, so nextInt == 0 gives the cheapest price.
 * Random variant choices (dye colours, unbiased pools) get the first item.
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

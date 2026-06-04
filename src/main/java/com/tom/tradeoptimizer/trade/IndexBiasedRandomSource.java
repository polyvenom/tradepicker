package com.tom.tradeoptimizer.trade;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A RandomSource where the first `nextInt(bound)` call returns the configured
 * target index (clamped into the bound), and every subsequent random call
 * returns 0/min. Lets us steer vanilla's enchant_randomly to a specific
 * enchantment while keeping every other random roll at the minimum end.
 *
 * Single-use — the bias is consumed on the first nextInt(int). Construct a new
 * instance per enchantment.
 */
public final class IndexBiasedRandomSource implements RandomSource {
    private final int target;
    private boolean consumed = false;
    private final RandomSource fallback = RandomSource.create(0L);

    public IndexBiasedRandomSource(int target) {
        this.target = target;
    }

    @Override public RandomSource fork() { return new IndexBiasedRandomSource(target); }
    @Override public PositionalRandomFactory forkPositional() { return fallback.forkPositional(); }
    @Override public void setSeed(long seed) {}

    @Override public int nextInt() { return 0; }

    @Override
    public int nextInt(int bound) {
        if (!consumed) {
            consumed = true;
            if (bound <= 0) return 0;
            return Math.min(target, bound - 1);
        }
        return 0;
    }

    @Override public long nextLong() { return 0L; }
    @Override public boolean nextBoolean() { return false; }
    @Override public float nextFloat() { return 0f; }
    @Override public double nextDouble() { return 0d; }
    @Override public double nextGaussian() { return 0d; }
}

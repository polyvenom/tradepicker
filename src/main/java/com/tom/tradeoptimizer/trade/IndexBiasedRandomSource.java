package com.tom.tradeoptimizer.trade;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A RandomSource that returns a pre-set sequence of integers for its first N
 * `nextInt(bound)` calls, then 0 forever after.
 *
 * Used to steer vanilla's enchant_randomly:
 *   - position 0 = HolderSet.getRandomElement → picks specific enchantment
 *   - position 1 = level roll (Mth.nextInt(min, max) → nextInt(max-min+1)) → picks specific level
 *   - positions 2+ = cost variance → 0 = vanilla's min cost
 *
 * Each call clamps the configured value into the bound (so position 0 returning 27
 * with a bound of 30 still gives 27; with a bound of 10 it gives 9).
 */
public final class IndexBiasedRandomSource implements RandomSource {
    private final int[] sequence;
    private int pos = 0;
    private final RandomSource fallback = RandomSource.create(0L);

    public IndexBiasedRandomSource(int... sequence) {
        this.sequence = sequence;
    }

    @Override public RandomSource fork() { return new IndexBiasedRandomSource(sequence); }
    @Override public PositionalRandomFactory forkPositional() { return fallback.forkPositional(); }
    @Override public void setSeed(long seed) {}

    @Override public int nextInt() { return 0; }

    @Override
    public int nextInt(int bound) {
        if (pos < sequence.length) {
            int v = sequence[pos++];
            if (bound <= 0) return 0;
            if (v < 0) return 0;
            return Math.min(v, bound - 1);
        }
        return 0;
    }

    @Override public long nextLong() { return 0L; }
    @Override public boolean nextBoolean() { return false; }
    @Override public float nextFloat() { return 0f; }
    @Override public double nextDouble() { return 0d; }
    @Override public double nextGaussian() { return 0d; }
}

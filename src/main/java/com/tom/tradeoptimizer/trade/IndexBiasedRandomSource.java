package com.tom.tradeoptimizer.trade;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A RandomSource that returns a pre-set sequence of integers for its first N
 * {@code nextInt(bound)} calls, then defers to a cost source after that.
 *
 * Used to steer vanilla's enchant_randomly:
 *   - position 0 = HolderSet.getRandomElement → picks specific enchantment
 *   - position 1 = level roll (Mth.nextInt(min, max) → nextInt(max-min+1)) → picks specific level
 *   - positions 2+ = cost variance
 *
 * The steered prefix is always honored so the targeted enchantment + level is exact. What
 * happens to the cost rolls after the prefix depends on {@code costFallback}:
 *   - null (default) → every later roll returns its minimum (0), i.e. vanilla's cheapest price.
 *   - non-null → later rolls delegate to that source, i.e. vanilla's randomized price range.
 *
 * Only rolls taken AFTER the steered prefix is fully consumed are delegated, so randomized
 * pricing can never disturb the enchantment/level selection.
 *
 * Each prefix call clamps the configured value into the bound (so position 0 returning 27
 * with a bound of 30 still gives 27; with a bound of 10 it gives 9).
 */
public final class IndexBiasedRandomSource implements RandomSource {
    private final int[] sequence;
    private int pos = 0;
    private final RandomSource costFallback; // nullable; used for cost rolls after the prefix
    private final RandomSource fallback = RandomSource.create(0L);

    public IndexBiasedRandomSource(int... sequence) {
        this(null, sequence);
    }

    public IndexBiasedRandomSource(RandomSource costFallback, int... sequence) {
        this.sequence = sequence;
        this.costFallback = costFallback;
    }

    private boolean prefixDone() {
        return pos >= sequence.length;
    }

    @Override public RandomSource fork() {
        return new IndexBiasedRandomSource(costFallback == null ? null : costFallback.fork(), sequence);
    }

    @Override public PositionalRandomFactory forkPositional() { return fallback.forkPositional(); }
    @Override public void setSeed(long seed) {}

    @Override public int nextInt() {
        return (costFallback != null && prefixDone()) ? costFallback.nextInt() : 0;
    }

    @Override
    public int nextInt(int bound) {
        if (pos < sequence.length) {
            int v = sequence[pos++];
            if (bound <= 0) return 0;
            if (v < 0) return 0;
            return Math.min(v, bound - 1);
        }
        return costFallback != null ? costFallback.nextInt(bound) : 0;
    }

    @Override public long nextLong() {
        return (costFallback != null && prefixDone()) ? costFallback.nextLong() : 0L;
    }

    @Override public boolean nextBoolean() {
        return costFallback != null && prefixDone() && costFallback.nextBoolean();
    }

    @Override public float nextFloat() {
        return (costFallback != null && prefixDone()) ? costFallback.nextFloat() : 0f;
    }

    @Override public double nextDouble() {
        return (costFallback != null && prefixDone()) ? costFallback.nextDouble() : 0d;
    }

    @Override public double nextGaussian() {
        return (costFallback != null && prefixDone()) ? costFallback.nextGaussian() : 0d;
    }
}

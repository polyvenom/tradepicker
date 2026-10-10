package com.tom.tradeoptimizer.trade;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;

/**
 * A RandomSource that returns a pre-set sequence of integers for its first N
 * {@code nextInt(bound)} calls, then defers to a cost source.
 *
 * Used to steer vanilla's enchant_randomly:
 *   - position 0 = the template's random pick from its own enchantment list → the enchantment
 *   - position 1 = level roll (Mth.nextInt(min, max) → nextInt(max-min+1)) → the level. Only for
 *     multi-level enchantments: Mth.nextInt draws nothing when min == max, so the caller leaves
 *     this slot out (OfferFactory.bookSource)
 *   - after the prefix = cost variance
 *
 * After the prefix, rolls return 0 (vanilla's cheapest price) when {@code costFallback} is null,
 * else delegate to it (randomized pricing). Only rolls after the prefix is fully consumed are
 * delegated, so pricing can never disturb the enchantment/level selection.
 *
 * Each prefix value is clamped into the call's bound (27 with bound 30 gives 27; bound 10 gives 9).
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

    /**
     * A fork is an independent stream, so it gets no steering (restarting the prefix would hand the
     * enchantment index to whatever forked mid-generation, #15). It continues the price stream:
     * seeded with vanilla pricing, minimum otherwise.
     */
    @Override public RandomSource fork() {
        return costFallback == null ? MinRandomSource.INSTANCE : costFallback.fork();
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

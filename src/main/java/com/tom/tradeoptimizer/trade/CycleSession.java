package com.tom.tradeoptimizer.trade;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.UUID;

/**
 * Per-player state for one ongoing cycle session.
 *
 * Lives on the server side, owned by CycleController. Phase machine:
 *   IDLE        → idle, can transition to BREAKING
 *   BREAKING    → block broken, waiting `cooldown` ticks
 *   PLACING     → block replaced, waiting `postPlaceWait` ticks for villager re-roll
 *   CHECKING    → re-read offers, evaluate match
 *   FOUND       → target located, awaiting user action (re-roll or stop)
 *   ENDED       → stopped, will be removed next tick
 */
public final class CycleSession {

    public enum Phase { IDLE, BREAKING, PLACING, CHECKING, FOUND, ENDED }

    public final UUID playerId;
    public final UUID villagerId;
    public final BlockPos workstation;
    public final Block originalBlock;
    public final ServerLevel level;
    public TradeSignature target;

    public Phase phase = Phase.IDLE;
    public int ticksLeft = 0;
    public int attempts = 0;
    public int lastCost = 0;
    public int bestCost = Integer.MAX_VALUE;

    public CycleSession(UUID playerId, UUID villagerId, BlockPos workstation,
                        Block originalBlock, ServerLevel level, TradeSignature target) {
        this.playerId = playerId;
        this.villagerId = villagerId;
        this.workstation = workstation;
        this.originalBlock = originalBlock;
        this.level = level;
        this.target = target;
    }

    /** Whether this session looks for a specific trade (auto-stop) vs. just doing single re-rolls. */
    public boolean hasTarget() {
        return target != null && !target.sellItemId().isEmpty();
    }
}

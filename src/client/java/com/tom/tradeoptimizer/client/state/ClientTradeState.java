package com.tom.tradeoptimizer.client.state;

import com.tom.tradeoptimizer.network.CycleStatusS2C;
import com.tom.tradeoptimizer.villager.VillagerEntry;

import java.util.Optional;

/**
 * Client-side memory: which villager the player is currently looking at, plus the
 * live cycle status pushed by the server. All access is single-threaded on the client.
 */
public final class ClientTradeState {
    private ClientTradeState() {}

    private static volatile Optional<VillagerEntry> snapshot = Optional.empty();
    private static volatile CycleStatusS2C cycleStatus = CycleStatusS2C.idle();

    public static void setSnapshot(Optional<VillagerEntry> v) {
        snapshot = v == null ? Optional.empty() : v;
    }

    public static Optional<VillagerEntry> snapshot() {
        return snapshot;
    }

    public static void setCycleStatus(CycleStatusS2C s) {
        cycleStatus = s != null ? s : CycleStatusS2C.idle();
    }

    public static CycleStatusS2C cycleStatus() {
        return cycleStatus;
    }

    public static void clear() {
        snapshot = Optional.empty();
        cycleStatus = CycleStatusS2C.idle();
    }
}

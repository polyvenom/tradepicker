package com.tom.tradeoptimizer.trade;

import com.tom.tradeoptimizer.TradeOptimizer;
import com.tom.tradeoptimizer.config.TradeOptimizerConfig;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.UUID;

public final class CycleController {
    private CycleController() {}

    private record PendingCycle(
            UUID villagerId,
            BlockPos workstation,
            Block originalBlock,
            ServerLevel level,
            ServerPlayer requester,
            int phase,
            int ticksLeft
    ) {}

    private static final Deque<PendingCycle> QUEUE = new ArrayDeque<>();
    private static boolean tickHooked = false;

    private static final Set<Block> WORKSTATIONS = Set.of(
            Blocks.LECTERN, Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.FLETCHING_TABLE,
            Blocks.GRINDSTONE, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.CARTOGRAPHY_TABLE,
            Blocks.BREWING_STAND, Blocks.COMPOSTER, Blocks.BARREL, Blocks.LOOM, Blocks.CAULDRON
    );

    public static void handleRequest(ServerPlayer player, UUID villagerId, BlockPos workstation) {
        TradeOptimizerConfig cfg = TradeOptimizerConfig.get();
        if (!cfg.cyclingEnabled) {
            player.sendSystemMessage(Component.literal("Trade Optimizer: cycling is disabled in config."));
            return;
        }

        ServerLevel level = (ServerLevel) player.level();
        if (level == null) return;
        if (!(level.getEntity(villagerId) instanceof Villager villager)) return;

        if (villager.getVillagerXp() > 0) {
            player.sendSystemMessage(Component.literal("Trade Optimizer: villager already has experience, cycling won't reset trades."));
            return;
        }

        BlockState state = level.getBlockState(workstation);
        Block block = state.getBlock();
        if (!WORKSTATIONS.contains(block)) {
            player.sendSystemMessage(Component.literal("Trade Optimizer: that block isn't a valid villager workstation."));
            return;
        }

        if (player.blockPosition().distSqr(workstation) > 64.0) {
            player.sendSystemMessage(Component.literal("Trade Optimizer: workstation is too far away (max 8 blocks)."));
            return;
        }

        QUEUE.add(new PendingCycle(villagerId, workstation, block, level, player, 0, cfg.cycleCooldownTicks));
        TradeOptimizer.LOGGER.info("Queued cycle for villager {} at {}", villagerId, workstation);
    }

    public static void register() {
        if (tickHooked) return;
        tickHooked = true;
        ServerTickEvents.END_SERVER_TICK.register(CycleController::tick);
    }

    private static void tick(MinecraftServer server) {
        if (QUEUE.isEmpty()) return;
        int size = QUEUE.size();
        for (int i = 0; i < size; i++) {
            PendingCycle p = QUEUE.pollFirst();
            if (p == null) break;
            PendingCycle next = step(p);
            if (next != null) QUEUE.add(next);
        }
    }

    private static PendingCycle step(PendingCycle p) {
        if (p.ticksLeft > 0) {
            return new PendingCycle(p.villagerId, p.workstation, p.originalBlock,
                    p.level, p.requester, p.phase, p.ticksLeft - 1);
        }
        int cooldown = TradeOptimizerConfig.get().cycleCooldownTicks;
        switch (p.phase) {
            case 0 -> {
                BlockState current = p.level.getBlockState(p.workstation);
                if (current.getBlock() != p.originalBlock) return null;
                p.level.destroyBlock(p.workstation, false, p.requester);
                return new PendingCycle(p.villagerId, p.workstation, p.originalBlock,
                        p.level, p.requester, 1, cooldown);
            }
            case 1 -> {
                return new PendingCycle(p.villagerId, p.workstation, p.originalBlock,
                        p.level, p.requester, 2, 0);
            }
            case 2 -> {
                p.level.setBlock(p.workstation, p.originalBlock.defaultBlockState(), 3);
                p.requester.sendSystemMessage(Component.literal("Trade Optimizer: cycle complete."));
                return null;
            }
        }
        return null;
    }
}
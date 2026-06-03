package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.villager.VillagerEntry;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NearbyScanner {
    private NearbyScanner() {}

    private static final Set<Block> WORKSTATIONS = Set.of(
            Blocks.LECTERN, Blocks.SMITHING_TABLE, Blocks.STONECUTTER, Blocks.FLETCHING_TABLE,
            Blocks.GRINDSTONE, Blocks.SMOKER, Blocks.BLAST_FURNACE, Blocks.CARTOGRAPHY_TABLE,
            Blocks.BREWING_STAND, Blocks.COMPOSTER, Blocks.BARREL, Blocks.LOOM, Blocks.CAULDRON
    );

    public static int countVacantWorkstations(MinecraftClient client, List<VillagerEntry> known) {
        if (client.player == null || client.world == null) return 0;
        World world = client.world;
        BlockPos center = client.player.getBlockPos();
        int radius = 32;

        Set<BlockPos> claimed = new HashSet<>();
        for (VillagerEntry v : known) claimed.add(v.pos());

        int vacant = 0;
        BlockPos.Mutable mp = new BlockPos.Mutable();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    mp.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    Block b = world.getBlockState(mp).getBlock();
                    if (!WORKSTATIONS.contains(b)) continue;
                    if (hasClaimedVillagerWithin(claimed, mp, 6)) continue;
                    vacant++;
                }
            }
        }
        return vacant;
    }

    private static boolean hasClaimedVillagerWithin(Set<BlockPos> claimed, BlockPos pos, int radius) {
        int r2 = radius * radius;
        for (BlockPos c : claimed) {
            if (c.getSquaredDistance(pos) <= r2) return true;
        }
        return false;
    }
}

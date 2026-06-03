package com.tom.tradeoptimizer.client.ui;

import com.tom.tradeoptimizer.villager.VillagerEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

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

    public static int countVacantWorkstations(Minecraft client, List<VillagerEntry> known) {
        if (client.player == null || client.level == null) return 0;
        Level level = client.level;
        BlockPos center = client.player.blockPosition();
        int radius = 32;

        Set<BlockPos> claimed = new HashSet<>();
        for (VillagerEntry v : known) claimed.add(v.pos());

        int vacant = 0;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -8; dy <= 8; dy++) {
                    mp.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    Block b = level.getBlockState(mp).getBlock();
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
            if (c.distSqr(pos) <= r2) return true;
        }
        return false;
    }
}
package com.tom.tradeoptimizer.villager;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.Villager;

/**
 * Opens the trade screen exactly as a right-click does after picking is done.
 *
 * onInteract itself can't run in a game test (it needs a client with the mod), and tests used to
 * reopen by re-submitting a level's picks. Since issue #9 a picked level can't be re-submitted,
 * so tests reopen through the same openMerchant the right-click path calls.
 */
public final class MerchantAccess {
    private MerchantAccess() {}

    public static void open(ServerPlayer player, Villager villager) {
        ProfileController.openMerchant(villager, player, villager.getVillagerData().getLevel());
    }
}

package com.tom.tradeoptimizer.villager;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

/**
 * Opens the trade screen exactly as a right-click does after picking is done.
 *
 * onInteract can't run in a game test (it needs a client with the mod), and a picked level can't
 * be re-submitted (#9), so tests reopen through the openMerchant the right-click path calls.
 */
public final class MerchantAccess {
    private MerchantAccess() {}

    public static void open(ServerPlayer player, Villager villager) {
        ProfileController.openMerchant(villager, player, villager.getVillagerData().level());
    }
}

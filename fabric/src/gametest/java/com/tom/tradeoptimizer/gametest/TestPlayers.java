package com.tom.tradeoptimizer.gametest;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Mock players placed where a real player would be: beside the test's villager.
 *
 * GameTestHelper.makeMockServerPlayerInLevel puts the player at the world spawn, far from the test
 * structure. Since issue #9 every villager action checks reach (ProfileController.canAct), so a
 * player left at spawn would be refused before the behaviour under test ever ran. Every test
 * spawns its villager at relative (1, 2, 1); this stands the player one block east of it.
 */
public final class TestPlayers {
    private TestPlayers() {}

    public static ServerPlayer near(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 2, 1.5));
        player.setPos(pos.x, pos.y, pos.z);
        return player;
    }
}

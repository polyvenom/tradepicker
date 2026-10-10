package com.tom.tradeoptimizer.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * Builds a ServerPlayer with a real Connection over an in-memory netty EmbeddedChannel and runs
 * the full PlayerList.placeNewPlayer flow, so it has a working ServerGamePacketListenerImpl.
 * Server flows that send packets (openMenu, openTradingScreen, system messages) run end-to-end;
 * the embedded channel swallows the packets.
 */
public final class MockPlayers {
    private MockPlayers() {}

    public static ServerPlayer mock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "test-mock-player");
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        // Registering the Connection as the channel's handler fires channelActive, binding the
        // channel so it can "send" packets.
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        return player;
    }

    /**
     * A mock player beside the test's villager: the harness builds players at the world spawn,
     * out of reach of ProfileController.canAct (#9). Tests spawn the villager at relative (1, 2, 1);
     * this stands the player one block east of it.
     */
    public static ServerPlayer near(GameTestHelper helper) {
        ServerPlayer player = mock(helper);
        Vec3 pos = helper.absoluteVec(new Vec3(2.5, 2, 1.5));
        player.setPos(pos.x, pos.y, pos.z);
        return player;
    }

    /**
     * Grant op (permission level 4) by writing the ops list directly: the GameTestServer's default
     * operator level can sit below the GAMEMASTERS threshold, so PlayerList.op() isn't deterministic.
     */
    public static void op(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        server.getPlayerList().getOps().add(new ServerOpListEntry(player.nameAndId(), LevelBasedPermissionSet.OWNER, false));
    }
}

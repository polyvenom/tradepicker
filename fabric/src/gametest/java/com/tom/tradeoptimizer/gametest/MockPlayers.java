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
import net.minecraft.server.players.ServerOpListEntry;

import java.util.UUID;

/**
 * 1.21.1 substitute for the 26.x {@code GameTestHelper.makeMockServerPlayerInLevel()}: builds a
 * ServerPlayer with a REAL Connection wired over an in-memory netty EmbeddedChannel and runs the
 * full PlayerList.placeNewPlayer flow, so the player has a working ServerGamePacketListenerImpl.
 * Server flows that send packets (openMenu, openTradingScreen, system messages) run end-to-end;
 * the packets are swallowed by the embedded channel.
 */
public final class MockPlayers {
    private MockPlayers() {}

    public static ServerPlayer mock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "test-mock-player");
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        // Registering the Connection as the channel's handler fires channelActive, which binds
        // the channel to the connection — after that it can "send" packets (into the void).
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        return player;
    }

    /**
     * Grant op (permission level 4) by writing the ops list directly — the GameTestServer's
     * default operator permission level can sit below the hasPermissions(2) threshold the mod
     * checks, so the bare PlayerList.op() isn't deterministic enough for tests.
     */
    public static void op(ServerPlayer player) {
        MinecraftServer server = player.serverLevel().getServer();
        server.getPlayerList().getOps().add(new ServerOpListEntry(player.getGameProfile(), 4, false));
    }
}

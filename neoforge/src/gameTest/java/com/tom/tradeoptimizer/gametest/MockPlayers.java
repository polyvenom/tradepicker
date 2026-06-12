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
 * 1.21.1 substitute for the 26.x {@code GameTestHelper.makeMockServerPlayerInLevel()} — same
 * recipe as the Fabric gametest twin: a ServerPlayer with a real Connection over an in-memory
 * netty EmbeddedChannel, run through the full PlayerList.placeNewPlayer flow so packet-sending
 * server code works end-to-end (packets vanish into the embedded channel).
 */
public final class MockPlayers {
    private MockPlayers() {}

    public static ServerPlayer mock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "test-mock-player");
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        return player;
    }

    /** Deterministic op grant: write a level-4 entry into the ops list directly. */
    public static void op(ServerPlayer player) {
        MinecraftServer server = player.serverLevel().getServer();
        server.getPlayerList().getOps().add(new ServerOpListEntry(player.getGameProfile(), 4, false));
    }
}

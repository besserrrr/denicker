package com.example.denicker;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.play.server.S01PacketJoinGame;
import net.minecraft.network.play.server.S07PacketRespawn;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3CPacketUpdateScore;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.network.play.server.S47PacketPlayerListHeaderFooter;
import net.minecraft.world.WorldSettings;

import java.util.UUID;

/**
 * Passive listener. Reads:
 *   S38PacketPlayerListItem  (tab list)
 *   S3EPacketTeams           (scoreboard teams)
 *   S3CPacketUpdateScore     (scoreboard scores)
 * plus S01 (join game) / S07 (respawn) only to reset state on a server transfer, like the proxy does.
 * Every packet is passed on unchanged.
 */
public class PacketSniffer extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof S38PacketPlayerListItem) {
                handleTab((S38PacketPlayerListItem) msg);
            } else if (msg instanceof S3EPacketTeams) {
                S3EPacketTeams p = (S3EPacketTeams) msg;
                Engine.onTeam(p.getAction(), p.getName(), p.getPlayers());
            } else if (msg instanceof S3CPacketUpdateScore) {
                S3CPacketUpdateScore p = (S3CPacketUpdateScore) msg;
                Engine.onScore(p.getPlayerName(), p.getScoreAction() == S3CPacketUpdateScore.Action.REMOVE);
            } else if (msg instanceof S47PacketPlayerListHeaderFooter) {
                Engine.onServerFooter(((S47PacketPlayerListHeaderFooter) msg).getFooter());
            } else if (msg instanceof S01PacketJoinGame || msg instanceof S07PacketRespawn) {
                Engine.sessionReset();
            }
        } catch (Throwable t) {
            t.printStackTrace(); // never break the connection because of us
        }
        super.channelRead(ctx, msg);
    }

    private static int gm(S38PacketPlayerListItem.AddPlayerData d) {
        WorldSettings.GameType t = d.getGameMode();
        return t == null ? 0 : t.getID();
    }

    private void handleTab(S38PacketPlayerListItem p) {
        S38PacketPlayerListItem.Action action = p.getAction();
        for (S38PacketPlayerListItem.AddPlayerData d : p.getEntries()) {
            GameProfile gp = d.getProfile();
            if (gp == null || gp.getId() == null) continue;
            UUID id = gp.getId();

            if (action == S38PacketPlayerListItem.Action.ADD_PLAYER) {
                Engine.onTabAdd(id, gp.getName(), gm(d));
            } else if (action == S38PacketPlayerListItem.Action.UPDATE_GAME_MODE) {
                Engine.onGameMode(id, gm(d));
            } else if (action == S38PacketPlayerListItem.Action.UPDATE_DISPLAY_NAME) {
                Engine.onDisplayName(id);
            } else if (action == S38PacketPlayerListItem.Action.REMOVE_PLAYER) {
                Engine.onTabRemove(id);
            }
        }
    }
}

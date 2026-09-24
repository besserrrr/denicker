package com.example.denicker;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3CPacketUpdateScore;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.util.IChatComponent;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/**
 * Passive listener: records data from the three packets and always passes the packet on unchanged.
 * NOTE: if a method name below doesn't compile with your MCP mappings, use the SRG name instead
 * (e.g. S38: func_179768_b = getAction, func_179767_a = getEntries).
 */
public class PacketSniffer extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        try {
            if (msg instanceof S38PacketPlayerListItem) {
                handleTab((S38PacketPlayerListItem) msg);
            } else if (msg instanceof S3EPacketTeams) {
                handleTeams((S3EPacketTeams) msg);
            } else if (msg instanceof S3CPacketUpdateScore) {
                handleScore((S3CPacketUpdateScore) msg);
            }
        } catch (Throwable t) {
            t.printStackTrace(); // never break the connection because of us
        }
        super.channelRead(ctx, msg); // pass the packet along untouched
    }

    private void handleTab(S38PacketPlayerListItem p) {
        S38PacketPlayerListItem.Action action = p.getAction();
        for (S38PacketPlayerListItem.AddPlayerData d : p.getEntries()) {
            GameProfile gp = d.getProfile();
            if (gp == null || gp.getId() == null) continue;
            UUID id = gp.getId();

            if (action == S38PacketPlayerListItem.Action.ADD_PLAYER) {
                if (gp.getName() != null) Denicker.tabNames.put(id, gp.getName());
                IChatComponent dn = d.getDisplayName();
                if (dn != null) Denicker.tabDisplay.put(id, dn.getUnformattedText());
            } else if (action == S38PacketPlayerListItem.Action.UPDATE_DISPLAY_NAME) {
                IChatComponent dn = d.getDisplayName();
                if (dn != null) Denicker.tabDisplay.put(id, dn.getUnformattedText());
                else Denicker.tabDisplay.remove(id);
            } else if (action == S38PacketPlayerListItem.Action.REMOVE_PLAYER) {
                Denicker.tabNames.remove(id);
                Denicker.tabDisplay.remove(id);
            }
        }
        Denicker.checkAndReport();
    }

    private void handleTeams(S3EPacketTeams p) {
        int mode = p.getAction();
        String team = p.getName();
        if (team == null) return;
        Collection<String> players = p.getPlayers();

        switch (mode) {
            case 0: // create
                Set<String> set = ConcurrentHashMap.newKeySet();
                if (players != null) set.addAll(players);
                Denicker.teams.put(team, set);
                store(team, p);
                break;
            case 1: // remove
                Denicker.teams.remove(team);
                Denicker.teamPrefix.remove(team);
                Denicker.teamSuffix.remove(team);
                break;
            case 2: // update info
                store(team, p);
                break;
            case 3: // add players
                Set<String> cur = Denicker.teams.get(team);
                if (cur == null) {
                    cur = ConcurrentHashMap.newKeySet();
                    Denicker.teams.put(team, cur);
                }
                if (players != null) cur.addAll(players);
                break;
            case 4: // remove players
                Set<String> cur2 = Denicker.teams.get(team);
                if (cur2 != null && players != null) cur2.removeAll(players);
                break;
        }
        Denicker.checkAndReport();
    }

    private void store(String team, S3EPacketTeams p) {
        if (p.getPrefix() != null) Denicker.teamPrefix.put(team, p.getPrefix());
        if (p.getSuffix() != null) Denicker.teamSuffix.put(team, p.getSuffix());
    }

    private void handleScore(S3CPacketUpdateScore p) {
        String entry = p.getPlayerName();
        if (entry == null) return;
        if (p.getScoreAction() == S3CPacketUpdateScore.Action.REMOVE) {
            Denicker.scoreEntries.remove(entry);
        } else {
            Denicker.scoreEntries.add(entry);
        }
        Denicker.checkAndReport();
    }
}

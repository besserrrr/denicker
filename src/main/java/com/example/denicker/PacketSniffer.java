package com.example.denicker;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.network.play.server.S3CPacketUpdateScore;
import net.minecraft.network.play.server.S3EPacketTeams;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Passive: records S38 / S3E / S3C data and passes every packet on unchanged. */
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
            t.printStackTrace();
        }
        super.channelRead(ctx, msg);
    }

    private void handleTab(S38PacketPlayerListItem p) {
        S38PacketPlayerListItem.Action action = p.getAction();
        for (S38PacketPlayerListItem.AddPlayerData d : p.getEntries()) {
            GameProfile gp = d.getProfile();
            if (gp == null || gp.getId() == null) continue;
            UUID id = gp.getId();

            if (action == S38PacketPlayerListItem.Action.ADD_PLAYER) {
                if (gp.getName() != null) {
                    Denicker.tabNames.put(id, gp.getName());
                    Denicker.noteTab(gp.getName());
                }
            } else if (action == S38PacketPlayerListItem.Action.REMOVE_PLAYER) {
                String n = Denicker.tabNames.remove(id);
                if (n != null) Denicker.tabSeen.remove(n.toLowerCase());
            }
        }
    }

    private void handleTeams(S3EPacketTeams p) {
        int mode = p.getAction();
        String team = p.getName();
        if (team == null) return;
        Collection<String> players = p.getPlayers();
        String src = "team:" + team;

        switch (mode) {
            case 0: { // create
                Set<String> set = ConcurrentHashMap.newKeySet();
                if (players != null) {
                    set.addAll(players);
                    for (String n : players) Denicker.noteName(n, src);
                }
                Denicker.teams.put(team, set);
                store(team, p);
                break;
            }
            case 1: { // remove
                Set<String> old = Denicker.teams.remove(team);
                if (old != null) for (String n : old) Denicker.forgetName(n, src);
                Denicker.teamPrefix.remove(team);
                Denicker.teamSuffix.remove(team);
                break;
            }
            case 2: // update info
                store(team, p);
                break;
            case 3: { // add players
                Set<String> cur = Denicker.teams.get(team);
                if (cur == null) {
                    cur = ConcurrentHashMap.newKeySet();
                    Denicker.teams.put(team, cur);
                }
                if (players != null) {
                    cur.addAll(players);
                    for (String n : players) Denicker.noteName(n, src);
                }
                break;
            }
            case 4: { // remove players
                Set<String> cur = Denicker.teams.get(team);
                if (players != null) {
                    if (cur != null) cur.removeAll(players);
                    for (String n : players) Denicker.forgetName(n, src);
                }
                break;
            }
        }
    }

    private void store(String team, S3EPacketTeams p) {
        if (p.getPrefix() != null) Denicker.teamPrefix.put(team, p.getPrefix());
        if (p.getSuffix() != null) Denicker.teamSuffix.put(team, p.getSuffix());
    }

    private void handleScore(S3CPacketUpdateScore p) {
        String entry = p.getPlayerName();
        if (entry == null) return;
        if (p.getScoreAction() == S3CPacketUpdateScore.Action.REMOVE) {
            Denicker.forgetName(entry, "score");
        } else {
            Denicker.noteName(entry, "score");
        }
    }
}

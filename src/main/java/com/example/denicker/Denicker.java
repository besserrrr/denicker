package com.example.denicker;

import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Packet-only denicker. Uses:
 *  S38PacketPlayerListItem -> names in the tab list (the nick is shown here)
 *  S3EPacketTeams          -> names inside scoreboard teams (a real name may appear here)
 *  S3CPacketUpdateScore    -> names used as scoreboard entries (a real name may appear here)
 *
 * Idea: a real name that shows up in team/score packets but has NO tab entry, and that arrived
 * at the same moment the nick was added to the tab list, is most likely that player's real name.
 */
@Mod(modid = Denicker.MODID, name = "Denicker", version = Denicker.VERSION,
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class Denicker {
    public static final String MODID = "denicker";
    public static final String VERSION = "1.2";

    /** A name and the moment (ms) + place it was first seen. */
    public static class Seen {
        public final String name;
        public final long time;
        public final String source;
        public Seen(String name, long time, String source) {
            this.name = name; this.time = time; this.source = source;
        }
    }

    public static final Map<UUID, String> tabNames = new ConcurrentHashMap<UUID, String>();
    public static final Map<String, Seen> tabSeen = new ConcurrentHashMap<String, Seen>();   // lower name -> tab add
    public static final Map<String, Seen> nameSeen = new ConcurrentHashMap<String, Seen>();  // lower name -> team/score
    public static final Map<String, Set<String>> teams = new ConcurrentHashMap<String, Set<String>>();
    public static final Map<String, String> teamPrefix = new ConcurrentHashMap<String, String>();
    public static final Map<String, String> teamSuffix = new ConcurrentHashMap<String, String>();
    public static volatile long connectTime = System.currentTimeMillis();

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        FMLCommonHandler.instance().bus().register(this);
        ClientCommandHandler.instance.registerCommand(new DenickCommand());
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent e) {
        clearAll();
        try {
            e.manager.channel().pipeline().addBefore("packet_handler", "denicker", new PacketSniffer());
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static void clearAll() {
        tabNames.clear();
        tabSeen.clear();
        nameSeen.clear();
        teams.clear();
        teamPrefix.clear();
        teamSuffix.clear();
        connectTime = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ recording (called by sniffer)
    public static void noteTab(String name) {
        if (name != null) tabSeen.putIfAbsent(name.toLowerCase(), new Seen(name, System.currentTimeMillis(), "tab"));
    }

    public static void noteName(String name, String source) {
        if (name != null) nameSeen.putIfAbsent(name.toLowerCase(), new Seen(name, System.currentTimeMillis(), source));
    }

    public static void forgetName(String name, String sourceOrNull) {
        if (name == null) return;
        Seen s = nameSeen.get(name.toLowerCase());
        if (s != null && (sourceOrNull == null || sourceOrNull.equals(s.source))) nameSeen.remove(name.toLowerCase());
    }

    // ------------------------------------------------------------------ analysis
    /** Valid-looking usernames present in team/score data but with no tab entry. */
    public static List<Seen> candidates() {
        List<Seen> out = new ArrayList<Seen>();
        for (Seen s : nameSeen.values()) {
            if (NAME.matcher(s.name).matches() && !tabSeen.containsKey(s.name.toLowerCase())) out.add(s);
        }
        return out;
    }

    /** Tab names that appear nowhere in team/score data. */
    public static List<Seen> orphans() {
        List<Seen> out = new ArrayList<Seen>();
        for (Map.Entry<String, Seen> en : tabSeen.entrySet()) {
            if (!nameSeen.containsKey(en.getKey())) out.add(en.getValue());
        }
        return out;
    }

    static boolean containsIgnoreCase(Collection<String> c, String s) {
        for (String x : c) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    static void checkPlayer(String target) {
        final Seen t = tabSeen.get(target.toLowerCase());
        if (t == null) {
            msg("\u00a7cNo tab entry named \u00a7e" + target + "\u00a7c. Type the nick exactly as in the tab list.");
            return;
        }
        String nick = t.name;
        msg("Checking \u00a7e" + nick + "\u00a7f (in tab " + ((System.currentTimeMillis() - t.time) / 1000)
                + "s ago, " + ((t.time - connectTime) / 1000.0) + "s after you joined)");

        boolean nickInData = nameSeen.containsKey(nick.toLowerCase());
        if (nickInData) {
            msg("Nick also appears in team/score data \u00a77(consistent name, less likely to leak)");
        } else {
            msg("Nick appears in NO team/score data \u00a77(suspicious: a real name may be used there instead)");
        }

        // Teams that contain the nick
        for (Map.Entry<String, Set<String>> en : teams.entrySet()) {
            if (containsIgnoreCase(en.getValue(), nick)) {
                for (String m : en.getValue()) {
                    if (!m.equalsIgnoreCase(nick)) {
                        msg("Team \u00a77" + en.getKey() + "\u00a7f also lists: \u00a7e" + m + " \u00a77(could be a teammate)");
                    }
                }
            }
        }

        // Unmatched names ranked by how close they arrived to the nick's tab entry
        final long ref = t.time;
        List<Seen> cands = candidates();
        if (cands.isEmpty()) {
            msg("No unmatched names: every team/score name is a tab player. Nothing leaked for this player.");
            return;
        }
        Collections.sort(cands, new Comparator<Seen>() {
            public int compare(Seen a, Seen b) {
                return Long.compare(Math.abs(a.time - ref), Math.abs(b.time - ref));
            }
        });

        boolean burst = (t.time - connectTime) < 3000;
        if (burst) {
            msg("\u00a77Note: this player was already there when you joined, so timing is unreliable.");
        }

        msg("Unmatched names closest in time:");
        int shown = 0;
        for (Seen s : cands) {
            msg("  \u00a7e" + s.name + " \u00a77(" + s.source + ", " + String.format("%+d", s.time - ref) + " ms)");
            if (++shown >= 6) break;
        }

        // Verdict
        List<Seen> orph = orphans();
        boolean targetOrphan = !nickInData;
        if (cands.size() == 1 && orph.size() == 1 && targetOrphan) {
            msg("\u00a7aVery likely real name: \u00a7e" + cands.get(0).name
                    + " \u00a7a(only unmatched name, and " + nick + " is the only unmatched tab entry)");
        } else if (!burst) {
            int close = 0;
            Seen best = null;
            for (Seen s : cands) {
                if (Math.abs(s.time - ref) <= 1000) { close++; if (best == null) best = s; }
            }
            if (close == 1 && targetOrphan) {
                msg("\u00a7aLikely real name: \u00a7e" + best.name + " \u00a7a(only unmatched name within 1s of the nick joining)");
            } else if (close > 1) {
                msg("\u00a7eSeveral names arrived at the same time, can't pick one.");
            } else {
                msg("No unmatched name arrived at the same time as the nick.");
            }
        }
    }

    // ------------------------------------------------------------------ helpers
    public static void msg(final String s) {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(new Runnable() {
            public void run() {
                if (mc.thePlayer != null) {
                    mc.thePlayer.addChatMessage(new ChatComponentText("\u00a7b[Denicker] \u00a7f" + s));
                }
            }
        });
    }

    private static String rel(long t) {
        return String.format("%.2fs", (t - connectTime) / 1000.0);
    }

    // ------------------------------------------------------------------ command
    public static class DenickCommand extends CommandBase {
        @Override public String getCommandName() { return "denick"; }

        @Override public String getCommandUsage(ICommandSender s) {
            return "/denick <nick>  |  /denick list|dump|clear";
        }

        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        @Override public int getRequiredPermissionLevel() { return 0; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length == 0) {
                msg("Usage: \u00a7e/denick <nick>\u00a7f  (also: list, dump, clear)");
                return;
            }
            String sub = args[0].toLowerCase();

            if (sub.equals("clear")) {
                clearAll();
                msg("Cleared.");

            } else if (sub.equals("list")) {
                List<Seen> c = candidates();
                if (c.isEmpty()) { msg("No unmatched names."); return; }
                Collections.sort(c, new Comparator<Seen>() {
                    public int compare(Seen a, Seen b) { return Long.compare(a.time, b.time); }
                });
                StringBuilder sb = new StringBuilder();
                for (Seen s : c) sb.append(s.name).append(" (").append(rel(s.time)).append(") ");
                msg("Unmatched names (not in tab): \u00a7e" + sb);

            } else if (sub.equals("dump")) {
                msg("Tab: " + tabSeen.size() + ", team/score names: " + nameSeen.size() + ", teams: " + teams.size());
                for (Seen s : tabSeen.values())
                    System.out.println("[Denicker] TAB " + s.name + " @" + rel(s.time));
                for (Seen s : nameSeen.values())
                    System.out.println("[Denicker] NAME " + s.name + " from " + s.source + " @" + rel(s.time));
                for (Map.Entry<String, Set<String>> en : teams.entrySet())
                    System.out.println("[Denicker] TEAM " + en.getKey() + " prefix=" + teamPrefix.get(en.getKey())
                            + " suffix=" + teamSuffix.get(en.getKey()) + " members=" + en.getValue());
                msg("Dump written to logs/latest.log");

            } else if (sub.equals("check") && args.length > 1) {
                checkPlayer(args[1]);

            } else {
                checkPlayer(args[0]);
            }
        }
    }
}

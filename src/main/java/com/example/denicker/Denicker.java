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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Mod(modid = Denicker.MODID, name = "Denicker", version = Denicker.VERSION,
        clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class Denicker {
    public static final String MODID = "denicker";
    public static final String VERSION = "1.0";

    // ---- State filled by PacketSniffer (netty thread), read by command/main thread ----
    public static final Map<UUID, String> tabNames = new ConcurrentHashMap<UUID, String>();
    public static final Map<UUID, String> tabDisplay = new ConcurrentHashMap<UUID, String>();
    public static final Map<String, Set<String>> teams = new ConcurrentHashMap<String, Set<String>>();
    public static final Map<String, String> teamPrefix = new ConcurrentHashMap<String, String>();
    public static final Map<String, String> teamSuffix = new ConcurrentHashMap<String, String>();
    public static final Set<String> scoreEntries = ConcurrentHashMap.newKeySet();
    private static final Set<String> reported = ConcurrentHashMap.newKeySet();

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        FMLCommonHandler.instance().bus().register(this);
        ClientCommandHandler.instance.registerCommand(new DenickCommand());
    }

    /** Inject our packet listener into the connection's netty pipeline. */
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
        tabDisplay.clear();
        teams.clear();
        teamPrefix.clear();
        teamSuffix.clear();
        scoreEntries.clear();
        reported.clear();
    }

    /**
     * Names that show up in team member lists / scoreboard entries but have NO tab-list entry.
     * On a server that leaks, these are the real names of nicked players.
     */
    public static Set<String> findCandidates() {
        Set<String> tab = new HashSet<String>();
        for (String n : tabNames.values()) tab.add(n.toLowerCase());

        Set<String> out = new TreeSet<String>();
        for (Set<String> members : teams.values()) {
            for (String n : members) consider(n, tab, out);
        }
        for (String n : scoreEntries) consider(n, tab, out);
        return out;
    }

    private static void consider(String n, Set<String> tab, Set<String> out) {
        if (n != null && NAME.matcher(n).matches() && !tab.contains(n.toLowerCase())) out.add(n);
    }

    /** Called by the sniffer after team/score/tab updates; prints newly found names once. */
    public static void checkAndReport() {
        for (String n : findCandidates()) {
            if (reported.add(n.toLowerCase())) {
                msg("\u00a7aPossible real name: \u00a7e" + n + " \u00a77(in team/score data, not in tab list)");
            }
        }
    }

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

    /** Looks up the current name for a UUID on Mojang's session server. Null if not a real account. */
    static String lookupName(UUID id) {
        try {
            URL url = new URL("https://sessionserver.mojang.com/session/minecraft/profile/"
                    + id.toString().replace("-", ""));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            Matcher m = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(sb.toString());
            return m.find() ? m.group(1) : null;
        } catch (Exception ex) {
            return null;
        }
    }

    // ------------------------------------------------------------------------------------
    public static class DenickCommand extends CommandBase {
        @Override public String getCommandName() { return "denick"; }

        @Override public String getCommandUsage(ICommandSender s) {
            return "/denick [list|dump|uuid|clear]";
        }

        @Override public boolean canCommandSenderUseCommand(ICommandSender s) { return true; }

        @Override public int getRequiredPermissionLevel() { return 0; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            String sub = args.length > 0 ? args[0].toLowerCase() : "list";

            if (sub.equals("clear")) {
                clearAll();
                msg("Cleared.");

            } else if (sub.equals("dump")) {
                // Raw data so you can see exactly what Pika sends and where a leak might be.
                msg("Tab entries: " + tabNames.size() + ", teams: " + teams.size()
                        + ", score entries: " + scoreEntries.size());
                for (Map.Entry<UUID, String> en : tabNames.entrySet()) {
                    String disp = tabDisplay.get(en.getKey());
                    System.out.println("[Denicker] TAB " + en.getKey() + " name=" + en.getValue()
                            + (disp != null ? " display=" + disp : ""));
                }
                for (Map.Entry<String, Set<String>> en : teams.entrySet()) {
                    System.out.println("[Denicker] TEAM " + en.getKey()
                            + " prefix=" + teamPrefix.get(en.getKey())
                            + " suffix=" + teamSuffix.get(en.getKey())
                            + " members=" + en.getValue());
                }
                for (String s : scoreEntries) System.out.println("[Denicker] SCORE " + s);
                msg("Full dump written to your Minecraft log / latest.log.");

            } else if (sub.equals("uuid")) {
                msg("Checking tab UUIDs against Mojang...");
                final Map<UUID, String> snapshot = new HashMap<UUID, String>(tabNames);
                new Thread(new Runnable() {
                    public void run() {
                        int found = 0;
                        for (Map.Entry<UUID, String> en : snapshot.entrySet()) {
                            String real = lookupName(en.getKey());
                            if (real != null && !real.equalsIgnoreCase(en.getValue())) {
                                found++;
                                msg("\u00a7e" + en.getValue() + " \u00a77is really \u00a7a" + real);
                            }
                            try { Thread.sleep(300); } catch (InterruptedException ie) { return; }
                        }
                        msg("UUID check done. Mismatches: " + found);
                    }
                }, "Denicker-UUID").start();

            } else { // list
                Set<String> c = findCandidates();
                if (c.isEmpty()) msg("No candidates. Try /denick uuid or /denick dump.");
                else msg("Candidates: \u00a7e" + c);
            }
        }
    }
}

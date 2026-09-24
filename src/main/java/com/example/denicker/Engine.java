package com.example.denicker;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Denick logic ported from the Pika proxy.
 *
 * How Pika leaks (as used by the proxy):
 *  1. NICK DETECTION: a tab-list name with no Pika profile (stats API 404) is a FAKE nick.
 *  2. TEAM MEMBER LEAK: Pika sorts the tab list with teams named "=<nick>A". The team's member list
 *     contains the REAL name, which has no tab entry.
 *  3. TEAM NAME LEAK: sometimes the team NAME is the real name and the member is the nick.
 *  4. TIMING FALLBACK: if no structural match, the leaked name that arrives within 2s (4s if only one
 *     nick) of a FAKE nick's tab entry belongs to that nick.
 *  Every leaked name is verified: it must be a real Pika profile with a nick-capable rank.
 */
public class Engine {
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final String PENDING = "__PENDING__";
    private static final String FAKE = "FAKE";
    private static final String SCORE_SRC = "score";
    private static final int LOBBY_TAB_SIZE = 17;          // more than this many tab entries = lobby
    private static final long KEEP_RESOLVED_MS = 10L * 60L * 1000L;

    public static volatile boolean announce = true;
    public static volatile boolean debug = false;
    public static volatile boolean tabRewrite = true;
    public static volatile boolean cleanLines = true;     // print [CLEAN] lines like the proxy console
    private static volatile UUID targetUuid = null;        // proxy's "reveal_uuid" feature
    private static volatile String targetLabel = null;
    private static volatile IChatComponent serverFooter = null;
    private static boolean footerModified = false;
    private static String lastFooterKey = null;

    private static final Object LOCK = new Object();
    private static final Map<UUID, String> uuidToName = new HashMap<UUID, String>();
    private static final Map<UUID, Integer> gameModes = new HashMap<UUID, Integer>();
    private static final Set<String> historicalTab = new HashSet<String>();
    private static final Set<String> historicalNormal = new HashSet<String>();
    private static final Map<String, Long> tabAddTimes = new HashMap<String, Long>();
    private static final Map<String, Set<String>> teams = new HashMap<String, Set<String>>();
    private static final Map<String, Long> sbAddTimes = new HashMap<String, Long>();
    private static final Set<String> checked = new HashSet<String>();
    private static final Map<String, Integer> retries = new HashMap<String, Integer>();
    private static final Set<String> activeLeaks = new HashSet<String>();
    /** real names seen in spectator teams (lower -> proper case) */
    private static final Map<String, String> spectators = new HashMap<String, String>();
    private static final Set<String> notifiedSpecs = new HashSet<String>();
    private static final Set<String> confirmedClean = new HashSet<String>();
    /** nick(lower) -> real name | "FAKE" | "__PENDING__real". Survives a server transfer for 10 min. */
    private static final Map<String, String> resolved = new HashMap<String, String>();
    private static final Map<String, Long> resolvedTime = new HashMap<String, Long>();
    private static final Set<UUID> modifiedTab = Collections.newSetFromMap(new ConcurrentHashMap<UUID, Boolean>());
    private static volatile int generation = 0;

    private static final ScheduledThreadPoolExecutor POOL = new ScheduledThreadPoolExecutor(4, new ThreadFactory() {
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Denicker-worker");
            t.setDaemon(true);
            return t;
        }
    });

    // =============================================================== helpers
    public static boolean valid(String n) {
        return n != null && NAME.matcher(n).matches();
    }

    private static void schedule(long ms, final Runnable r) {
        POOL.schedule(new Runnable() {
            public void run() {
                try { r.run(); } catch (Throwable t) { t.printStackTrace(); }
            }
        }, ms, TimeUnit.MILLISECONDS);
    }

    private static void post(final String text) {
        final Minecraft mc = Minecraft.getMinecraft();
        mc.addScheduledTask(new Runnable() {
            public void run() {
                if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText(text));
            }
        });
    }

    public static void msg(String s) { post("\u00a7b[Denicker] \u00a7f" + s); }

    private static void announce(String s) { if (announce) msg(s); }

    private static void debug(String s) { if (debug) post("\u00a78[Denicker dbg] \u00a77" + s); }

    private static boolean isRealMapping(String v) {
        return v != null && !FAKE.equals(v) && !v.startsWith(PENDING);
    }

    private static boolean containsIgnoreCase(Collection<String> c, String s) {
        for (String x : c) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    static boolean isSpectatorTeam(String team) {
        String t = team.toLowerCase();
        return t.equals("-1-none") || t.startsWith("-1-") || t.contains("spectator") || t.contains("spec");
    }

    /** Pika tab-order team names look like "=<nick>A". Returns the nick part, or null. */
    private static String extractNick(String team) {
        if (team.startsWith("=") && team.endsWith("A") && team.length() > 2) return team.substring(1, team.length() - 1);
        if (team.startsWith("=") && team.length() > 1) return team.substring(1);
        return null;
    }

    private static String altExtract(String team) {
        if (team.startsWith("=") && team.length() > 1) return team.substring(1);
        return null;
    }

    private static boolean teamPointsTo(String team, String nick) {
        if (team.equalsIgnoreCase(nick)) return true;
        String a = extractNick(team);
        String b = altExtract(team);
        return (a != null && a.equalsIgnoreCase(nick)) || (b != null && b.equalsIgnoreCase(nick));
    }

    /** The tab name a team name refers to (extracted nick, alt, or the team name itself), or null. */
    private static String tabNameForTeam(String team, Map<String, String> activeLower) {
        String a = extractNick(team);
        String b = altExtract(team);
        if (a != null && activeLower.containsKey(a.toLowerCase())) return activeLower.get(a.toLowerCase());
        if (b != null && activeLower.containsKey(b.toLowerCase())) return activeLower.get(b.toLowerCase());
        if (activeLower.containsKey(team.toLowerCase())) return activeLower.get(team.toLowerCase());
        return null;
    }

    private static Map<String, String> activeLowerLocked() {
        Map<String, String> m = new HashMap<String, String>();
        for (String n : uuidToName.values()) m.put(n.toLowerCase(), n);
        return m;
    }

    private static boolean isNickedLocked(String nick) {
        return nick != null && FAKE.equals(resolved.get(nick.toLowerCase()));
    }

    private static String nickFromRealLocked(String real) {
        for (Map.Entry<String, String> e : resolved.entrySet()) {
            if (isRealMapping(e.getValue()) && e.getValue().equalsIgnoreCase(real)) {
                String proper = activeLowerLocked().get(e.getKey());
                return proper != null ? proper : e.getKey();
            }
        }
        return null;
    }

    private static boolean isNormalPlayerLocked(String name, String real) {
        if (name != null && historicalNormal.contains(name.toLowerCase())) return true;
        if (real != null && historicalNormal.contains(real.toLowerCase())) return true;
        if (name != null) {
            String r = resolved.get(name.toLowerCase());
            if (isRealMapping(r) && historicalNormal.contains(r.toLowerCase())) return true;
            String nick = nickFromRealLocked(name);
            if (nick != null && historicalNormal.contains(nick.toLowerCase())) return true;
        }
        return false;
    }

    private static boolean bumpRetry(String key, int max) {
        synchronized (LOCK) {
            Integer n = retries.get(key);
            int c = n == null ? 0 : n.intValue();
            if (c >= max) return false;
            retries.put(key, Integer.valueOf(c + 1));
            return true;
        }
    }

    /** proxy console detail: (Level x | Rank: y | Clan: z) with the API colours */
    private static String detail(PikaApi.Result r) {
        StringBuilder sb = new StringBuilder();
        if (r.level > 0) sb.append("Level: \u00a76").append(r.level);
        if (!r.bestRankColored.isEmpty()) sb.append(sb.length() > 0 ? " \u00a77| " : "").append("Rank: ").append(r.bestRankColored);
        if (!r.clanColored.isEmpty()) sb.append(sb.length() > 0 ? " \u00a77| " : "").append("Clan: ").append(r.clanColored);
        return sb.toString();
    }

    private static void announceDetail(PikaApi.Result r) {
        String d = detail(r);
        if (!d.isEmpty()) announce("\u00a77   > " + d);
    }

    private static String info(PikaApi.Result r) {
        StringBuilder sb = new StringBuilder();
        if (r.level > 0) sb.append(" \u00a77| Level: \u00a76").append(r.level);
        if (!r.bestRank.isEmpty()) sb.append(" \u00a77| Rank: \u00a7f").append(r.bestRank);
        if (!r.clan.isEmpty()) sb.append(" \u00a77| Clan: \u00a7f").append(r.clan);
        return sb.toString();
    }

    // =============================================================== resets
    /** Full wipe (new connection or /denick clear). */
    public static void fullReset() {
        synchronized (LOCK) {
            clearSessionLocked();
            resolved.clear();
            resolvedTime.clear();
            confirmedClean.clear();
            generation++;
        }
        PikaApi.clearCache();
        serverFooter = null;
        lastFooterKey = null;
    }

    /** Server transfer / respawn (S01 / S07): clear tab + scoreboard state, keep recent denicks. */
    public static void sessionReset() {
        synchronized (LOCK) {
            clearSessionLocked();
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, Long>> it = resolvedTime.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (now - e.getValue().longValue() > KEEP_RESOLVED_MS) {
                    resolved.remove(e.getKey());
                    it.remove();
                }
            }
            // drop half-finished claims
            Iterator<Map.Entry<String, String>> it2 = resolved.entrySet().iterator();
            while (it2.hasNext()) {
                if (it2.next().getValue().startsWith(PENDING)) it2.remove();
            }
            generation++;
        }
        modifiedTab.clear();
        lastFooterKey = null;
        debug("Session data reset (server transfer / respawn)");
    }

    private static void clearSessionLocked() {
        uuidToName.clear();
        gameModes.clear();
        historicalTab.clear();
        historicalNormal.clear();
        tabAddTimes.clear();
        teams.clear();
        sbAddTimes.clear();
        checked.clear();
        retries.clear();
        activeLeaks.clear();
        spectators.clear();
        notifiedSpecs.clear();
    }

    // =============================================================== S38: tab list
    public static void onTabAdd(UUID id, String name, int gm) {
        if (!valid(name)) return;
        final String lower = name.toLowerCase();
        String status = null;
        synchronized (LOCK) {
            uuidToName.put(id, name);
            gameModes.put(id, Integer.valueOf(gm));
            historicalTab.add(lower);
            if (gm != 3) historicalNormal.add(lower);
            tabAddTimes.put(lower, Long.valueOf(System.currentTimeMillis()));
            status = resolved.get(lower);
            if (isRealMapping(status)) {
                historicalTab.add(status.toLowerCase());
                activeLeaks.add(status.toLowerCase());
            }
        }
        final int g = generation;
        final UUID fid = id;
        final String fname = name;

        UUID tgt = targetUuid;
        if (tgt != null && tgt.equals(id)) { // proxy's reveal_uuid: this tab UUID is the target
            synchronized (LOCK) {
                resolved.put(lower, targetLabel);
                resolvedTime.put(lower, Long.valueOf(System.currentTimeMillis()));
            }
            announce("\u00a7d\u00a7l[DENICK] \u00a7r\u00a7e" + name + " \u00a77= \u00a7b\u00a7l" + targetLabel);
            applyDisplay(id);
            return;
        }
        if (status != null) applyDisplay(id);
        if (status == null || FAKE.equals(status)) {
            schedule(0, new Runnable() { public void run() { checkPlayer(fid, fname, g); } });
        }
        // scoreboard teams may have arrived BEFORE this tab entry
        schedule(0, new Runnable() { public void run() { retroactiveCheck(fname, g); } });

        // a player who was "leaked" earlier is now visible in tab (un-vanished / un-nicked)
        String leaveTeam = null;
        synchronized (LOCK) {
            for (Map.Entry<String, Set<String>> e : teams.entrySet()) {
                if (containsIgnoreCase(e.getValue(), name)) { leaveTeam = e.getKey(); break; }
            }
        }
        if (leaveTeam != null) handleLeave(name, leaveTeam);
    }

    public static void onGameMode(UUID id, int gm) {
        synchronized (LOCK) {
            gameModes.put(id, Integer.valueOf(gm));
            String n = uuidToName.get(id);
            if (n != null && gm != 3) historicalNormal.add(n.toLowerCase());
        }
    }

    public static void onTabRemove(UUID id) {
        synchronized (LOCK) {
            uuidToName.remove(id);
            gameModes.remove(id);
        }
        modifiedTab.remove(id);
    }

    public static void onDisplayName(UUID id) {
        applyDisplay(id); // server changed the tab text; re-apply ours
    }

    /** Step 1: is this tab name a real Pika account? 404 = FAKE nick. */
    private static void checkPlayer(UUID id, String name, int g) {
        if (g != generation) return;
        String lower = name.toLowerCase();
        synchronized (LOCK) {
            if (!checked.add(lower)) return;
        }
        PikaApi.Result r = PikaApi.lookup(name);
        if (g != generation) return;

        if (r.status == PikaApi.Status.OK) {
            synchronized (LOCK) { confirmedClean.add(lower); }
            if (cleanLines) {
                StringBuilder sb = new StringBuilder("(Level " + r.level);
                if (!r.bestRank.isEmpty()) sb.append(" | Rank: ").append(r.bestRank);
                if (!r.clan.isEmpty()) sb.append(" | Clan: ").append(r.clan);
                msg("\u00a7a[CLEAN] " + name + " \u00a77" + sb + ")");
            }
            applyDisplay(id);
        } else if (r.status == PikaApi.Status.MISSING) {
            boolean fresh = false;
            synchronized (LOCK) {
                if (resolved.get(lower) == null) {
                    resolved.put(lower, FAKE);
                    resolvedTime.put(lower, Long.valueOf(System.currentTimeMillis()));
                    fresh = true;
                }
            }
            if (fresh) announce("\u00a7d\u00a7l[DENICK] \u00a7r\u00a7e" + name + " \u00a7c\u00a7lFAKE");
            applyDisplay(id);
        } else {
            synchronized (LOCK) { checked.remove(lower); }
            debug("API error for " + name);
            if (bumpRetry("tab:" + lower, 1)) {
                final UUID fid = id;
                final String fname = name;
                final int fg = g;
                schedule(4000, new Runnable() { public void run() { checkPlayer(fid, fname, fg); } });
            }
        }
    }

    /** Scoreboard teams that arrived before this nick's tab entry: re-run the leak check for them. */
    private static void retroactiveCheck(String nick, int g) {
        List<String[]> hits = new ArrayList<String[]>();
        synchronized (LOCK) {
            for (Map.Entry<String, Set<String>> e : teams.entrySet()) {
                String t = e.getKey();
                if (!teamPointsTo(t, nick)) continue;
                for (String m : e.getValue()) {
                    if (!valid(m) || m.equalsIgnoreCase(nick)) continue;
                    if (historicalTab.contains(m.toLowerCase())) continue;
                    hits.add(new String[] { m, t });
                }
            }
        }
        for (String[] h : hits) scheduleLeakCheck(h[0], h[1], g, 1200);
    }

    // =============================================================== S3E: teams
    public static void onTeam(int mode, String team, Collection<String> players) {
        if (team == null) return;
        final int g = generation;
        List<String> plist = players == null ? new ArrayList<String>() : new ArrayList<String>(players);

        if (mode == 0 || mode == 3) {
            Map<String, String> activeLower;
            synchronized (LOCK) {
                Set<String> set = teams.get(team);
                if (set == null || mode == 0) {
                    set = new HashSet<String>();
                    teams.put(team, set);
                }
                set.addAll(plist);
                activeLower = activeLowerLocked();
            }
            long now = System.currentTimeMillis();
            for (String p : plist) {
                if (!valid(p)) continue;
                synchronized (LOCK) { sbAddTimes.put(p.toLowerCase(), Long.valueOf(now)); }
                if (!activeLower.containsKey(p.toLowerCase())) {
                    scheduleLeakCheck(p, team, g, 1200);
                }
            }
            // team NAME leak: team name is a real-looking username that is not in tab, member is a tab nick
            if (valid(team) && !activeLower.containsKey(team.toLowerCase())) {
                for (String p : plist) {
                    String matched = activeLower.get(p.toLowerCase());
                    if (matched != null) scheduleTeamNameCheck(team, matched, g, 1200);
                }
            }
        } else if (mode == 1) {
            Set<String> old;
            synchronized (LOCK) { old = teams.remove(team); }
            if (old != null) for (String p : old) handleLeave(p, team);
        } else if (mode == 4) {
            synchronized (LOCK) {
                Set<String> set = teams.get(team);
                if (set != null) set.removeAll(plist);
            }
            for (String p : plist) handleLeave(p, team);
        }
    }

    private static void scheduleLeakCheck(final String player, final String team, final int g, long delay) {
        schedule(delay, new Runnable() { public void run() { checkScoreboardLeak(player, team, g); } });
    }

    private static void scheduleTeamNameCheck(final String team, final String nick, final int g, long delay) {
        schedule(delay, new Runnable() { public void run() { checkTeamNameLeak(team, nick, g); } });
    }

    // =============================================================== S3C: scores
    /** Score entries can also carry a leaked name. Low confidence: only accepted on a timing match. */
    public static void onScore(String entry, boolean remove) {
        if (entry == null || !valid(entry) || remove) return;
        String lower = entry.toLowerCase();
        boolean inTab;
        synchronized (LOCK) {
            if (!sbAddTimes.containsKey(lower)) sbAddTimes.put(lower, Long.valueOf(System.currentTimeMillis()));
            inTab = historicalTab.contains(lower);
        }
        if (!inTab) scheduleLeakCheck(entry, SCORE_SRC, generation, 1200);
    }

    // =============================================================== leak checks
    private static void checkScoreboardLeak(String player, String team, int g) {
        if (g != generation) return;
        String tl = team.toLowerCase();
        if (tl.startsWith("npc") || tl.startsWith("cit") || tl.startsWith("hologram")) return;

        if (isSpectatorTeam(team)) {
            handleSpectator(player, team);
            return;
        }

        final String pl = player.toLowerCase();
        String nickname = null;
        boolean nickedUser = false;
        int tabCount;
        boolean patternTeam = extractNick(team) != null;

        synchronized (LOCK) {
            if (historicalTab.contains(pl)) return;
            for (String n : uuidToName.values()) if (n.equalsIgnoreCase(player)) return;
            if (!checked.add(pl)) return;

            Map<String, String> activeLower = activeLowerLocked();
            tabCount = uuidToName.size();
            String primary = extractNick(team);
            String alt = altExtract(team);
            String extractedInTab = null;
            if (primary != null && activeLower.containsKey(primary.toLowerCase())) {
                extractedInTab = activeLower.get(primary.toLowerCase());
            } else if (alt != null && activeLower.containsKey(alt.toLowerCase())) {
                extractedInTab = activeLower.get(alt.toLowerCase());
            }

            // S1: team "=<nick>A" points at a FAKE tab player (definitive)
            if (extractedInTab != null && isNickedLocked(extractedInTab)) nickname = extractedInTab;

            // S2: the team name itself is the nick
            if (nickname == null && activeLower.containsKey(tl)) {
                String c = activeLower.get(tl);
                if (isNickedLocked(c)) nickname = c;
            }

            // already resolved to a real name: duplicate packet
            if (nickname != null && isRealMapping(resolved.get(nickname.toLowerCase()))) return;

            // S3: extracted nick is in tab but its API check has not finished yet (race)
            if (nickname == null && extractedInTab != null) {
                String st = resolved.get(extractedInTab.toLowerCase());
                if (st == null || FAKE.equals(st)) nickname = extractedInTab;
            }

            // S4: cross-team scan for a team pointing at a FAKE tab player that lists this name
            if (nickname == null) {
                for (Map.Entry<String, Set<String>> e : teams.entrySet()) {
                    String tn = tabNameForTeam(e.getKey(), activeLower);
                    if (tn == null || !containsIgnoreCase(e.getValue(), player)) continue;
                    String st = resolved.get(tn.toLowerCase());
                    if (FAKE.equals(st)) { nickname = tn; break; }
                }
            }

            nickedUser = nickname != null && isNickedLocked(nickname);

            // S5: timing match against FAKE nicks (2s window if several, 4s if only one)
            if (nickname == null || !nickedUser) {
                List<String> cands = new ArrayList<String>();
                for (String n : activeLower.values()) {
                    if (FAKE.equals(resolved.get(n.toLowerCase()))) cands.add(n);
                }
                if (!cands.isEmpty()) {
                    Long sb = sbAddTimes.get(pl);
                    if (cands.size() == 1 && sb == null) {
                        nickname = cands.get(0);
                        nickedUser = true;
                    } else if (sb != null) {
                        String best = null;
                        long minDiff = Long.MAX_VALUE;
                        for (String c : cands) {
                            Long tt = tabAddTimes.get(c.toLowerCase());
                            if (tt == null) continue;
                            long d = Math.abs(sb.longValue() - tt.longValue());
                            if (d < minDiff) { minDiff = d; best = c; }
                        }
                        long window = cands.size() > 1 ? 2000L : 4000L;
                        if (best != null && minDiff < window) {
                            nickname = best;
                            nickedUser = true;
                        }
                    }
                }
            }

            // score-entry source has no structure: only a timing match counts
            if (SCORE_SRC.equals(team) && !nickedUser) {
                checked.remove(pl);
                nickname = null;
            } else {
                if (nickname == null) nickname = primary != null ? primary : team;
                if (nickedUser) {
                    String ex = resolved.get(nickname.toLowerCase());
                    if (ex != null && !FAKE.equals(ex)) return;
                    resolved.put(nickname.toLowerCase(), PENDING + player);
                }
            }
        }

        if (nickname == null) { // score entry without a timing match yet: retry a couple of times
            if (bumpRetry("score:" + pl, 2)) scheduleLeakCheck(player, team, g, 1500);
            return;
        }

        final String nick = nickname;
        final boolean claimed = nickedUser;

        PikaApi.Result r = PikaApi.lookup(player);
        if (g != generation) return;

        if (r.status == PikaApi.Status.ERROR) {
            release(nick, player, claimed);
            synchronized (LOCK) { checked.remove(pl); }
            debug("API error for leaked name " + player);
            if (bumpRetry("leak:" + pl, 1)) scheduleLeakCheck(player, team, g, 3000);
            return;
        }
        if (r.status == PikaApi.Status.MISSING) {
            release(nick, player, claimed);
            debug("Leak ignored: " + player + " is not a Pika profile (404)");
            return;
        }
        if (!PikaApi.canNick(r)) {
            release(nick, player, claimed);
            debug("Leak ignored: " + player + " has no nick-capable rank");
            return;
        }

        // Lobby: a nick-capable name in team data with no tab entry is a vanished staff member
        if (tabCount > LOBBY_TAB_SIZE) {
            release(nick, player, claimed);
            if (PikaApi.isStaff(r)) {
                String rk = r.bestRankColored.isEmpty() ? "Staff" : r.bestRankColored;
                announce("\u00a76\u00a7l[VANISH] \u00a7r\u00a7e" + player + " \u00a77(" + rk + "\u00a77) has vanished!");
            } else {
                debug("Leak ignored in lobby: " + player);
            }
            return;
        }

        UUID nickId = null;
        synchronized (LOCK) {
            for (Map.Entry<UUID, String> e : uuidToName.entrySet()) {
                if (e.getValue().equalsIgnoreCase(nick)) { nickId = e.getKey(); break; }
            }
            if (nickId != null) {
                resolved.put(nick.toLowerCase(), player);
                resolvedTime.put(nick.toLowerCase(), Long.valueOf(System.currentTimeMillis()));
                activeLeaks.add(pl);
            }
        }

        if (nickId == null) {
            // Nick has no tab entry (yet).
            release(nick, player, claimed);
            if (patternTeam) {
                // "=<nick>A" team but the nick is not in tab yet: retroactiveCheck() re-runs this when it appears
                synchronized (LOCK) { checked.remove(pl); }
                debug("Waiting for tab entry of " + nick + " (real name " + player + ")");
            } else {
                announce("\u00a76\u00a7l[HIDDEN] \u00a7r\u00a7b" + player
                        + " \u00a77is in team " + team + " but not in the tab list");
                announceDetail(r);
            }
            return;
        }

        announce("\u00a7d\u00a7l[DENICK] \u00a7r\u00a7e" + nick + " \u00a77= \u00a7b\u00a7l" + player);
        announceDetail(r);
        applyDisplay(nickId);
    }

    /** Give the nick back (FAKE) after a rejected leak so a later valid leak can still claim it. */
    private static void release(String nick, String player, boolean claimed) {
        if (!claimed || nick == null) return;
        synchronized (LOCK) {
            if ((PENDING + player).equals(resolved.get(nick.toLowerCase()))) {
                resolved.put(nick.toLowerCase(), FAKE);
            }
        }
    }

    private static void checkTeamNameLeak(String team, String nickIn, int g) {
        if (g != generation) return;
        String tl = team.toLowerCase();
        if (tl.startsWith("npc") || tl.startsWith("cit") || tl.startsWith("hologram")) return;

        String nick = nickIn;
        int tabCount;
        synchronized (LOCK) {
            String ex = resolved.get(nick.toLowerCase());
            if (isRealMapping(ex)) return;
            if (activeLeaks.contains(tl)) return;
            if (!checked.add(tl)) return;
            tabCount = uuidToName.size();

            // if this tab player is not nicked, redirect to the FAKE nick that joined at the same time
            if (!FAKE.equals(ex)) {
                Map<String, String> activeLower = activeLowerLocked();
                List<String> cands = new ArrayList<String>();
                for (String n : activeLower.values()) {
                    if (FAKE.equals(resolved.get(n.toLowerCase()))) cands.add(n);
                }
                Long sb = sbAddTimes.get(tl);
                if (cands.size() == 1) {
                    Long tt = tabAddTimes.get(cands.get(0).toLowerCase());
                    if (sb == null || tt == null || Math.abs(sb.longValue() - tt.longValue()) < 3000L) {
                        nick = cands.get(0);
                    }
                } else if (!cands.isEmpty() && sb != null) {
                    String best = null;
                    long minDiff = Long.MAX_VALUE;
                    for (String c : cands) {
                        Long tt = tabAddTimes.get(c.toLowerCase());
                        if (tt == null) continue;
                        long d = Math.abs(sb.longValue() - tt.longValue());
                        if (d < minDiff) { minDiff = d; best = c; }
                    }
                    if (best != null && minDiff < 2000L) nick = best;
                }
            }
        }

        PikaApi.Result r = PikaApi.lookup(team);
        if (g != generation) return;
        if (r.status == PikaApi.Status.ERROR) {
            synchronized (LOCK) { checked.remove(tl); }
            if (bumpRetry("tname:" + tl, 1)) scheduleTeamNameCheck(team, nick, g, 3000);
            return;
        }
        if (r.status == PikaApi.Status.MISSING) { debug("Team-name leak ignored: " + team + " (404)"); return; }
        if (!PikaApi.canNick(r)) { debug("Team-name leak ignored: " + team + " (no nick rank)"); return; }

        if (tabCount > LOBBY_TAB_SIZE) {
            if (PikaApi.isStaff(r)) {
                String rk = r.bestRankColored.isEmpty() ? "Staff" : r.bestRankColored;
                announce("\u00a76\u00a7l[VANISH] \u00a7r\u00a7e" + team + " \u00a77(" + rk + "\u00a77) has vanished!");
            }
            return;
        }

        UUID nickId = null;
        synchronized (LOCK) {
            for (Map.Entry<UUID, String> e : uuidToName.entrySet()) {
                if (e.getValue().equalsIgnoreCase(nick)) { nickId = e.getKey(); break; }
            }
            if (nickId != null) {
                resolved.put(nick.toLowerCase(), team);
                resolvedTime.put(nick.toLowerCase(), Long.valueOf(System.currentTimeMillis()));
                activeLeaks.add(tl);
            }
        }
        if (nickId == null) return;
        announce("\u00a7d\u00a7l[DENICK] \u00a7r\u00a7e" + nick + " \u00a77= \u00a7b\u00a7l" + team);
        announceDetail(r);
        applyDisplay(nickId);
    }

    private static void handleSpectator(String player, String team) {
        String pl = player.toLowerCase();
        synchronized (LOCK) {
            String nick = nickFromRealLocked(player);
            if (isNormalPlayerLocked(nick != null ? nick : player, player)) return; // dead player of this game
            activeLeaks.add(pl);
            spectators.put(pl, player); // chat message + tab footer are produced by tickSpectators()
        }
        debug("[SPECTATOR JOIN] " + player + " (team " + team + ")");
    }

    private static void handleLeave(String player, String team) {
        String pl = player.toLowerCase();
        boolean wasSpec;
        synchronized (LOCK) { wasSpec = spectators.remove(pl) != null; }
        if (wasSpec) debug("[SPECTATOR LEFT] " + player + " (team " + team + ")");

        List<UUID> refresh = new ArrayList<UUID>();
        synchronized (LOCK) {
            if (activeLeaks.remove(pl)) {
                Iterator<Map.Entry<String, String>> it = resolved.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, String> e = it.next();
                    String v = e.getValue();
                    if (isRealMapping(v) && (v.equalsIgnoreCase(player) || v.equalsIgnoreCase(team))) {
                        for (Map.Entry<UUID, String> u : uuidToName.entrySet()) {
                            if (u.getValue().equalsIgnoreCase(e.getKey())) refresh.add(u.getKey());
                        }
                        resolvedTime.remove(e.getKey());
                        it.remove();
                    }
                }
                checked.remove(pl);
            }
        }
        for (UUID id : refresh) applyDisplay(id);
    }

    // =============================================================== tab list text
    private static void applyDisplay(final UUID id) {
        if (!tabRewrite) return;
        schedule(300, new Runnable() {
            public void run() {
                final String text;
                synchronized (LOCK) {
                    String nick = uuidToName.get(id);
                    String st = nick == null ? null : resolved.get(nick.toLowerCase());
                    if (st == null || st.startsWith(PENDING) || (nick != null && confirmedClean.contains(nick.toLowerCase()) && FAKE.equals(st))) {
                        text = null;
                    } else if (FAKE.equals(st)) {
                        text = "\u00a7e" + nick + " \u00a78\u00bb \u00a7c\u00a7lFAKE";
                    } else {
                        text = "\u00a7e" + nick + " \u00a78\u00bb \u00a7a\u00a7l" + st;
                    }
                }
                final Minecraft mc = Minecraft.getMinecraft();
                mc.addScheduledTask(new Runnable() {
                    public void run() {
                        NetHandlerPlayClient nh = mc.getNetHandler();
                        if (nh == null) return;
                        NetworkPlayerInfo pi = nh.getPlayerInfo(id);
                        if (pi == null) return;
                        if (text == null) {
                            if (modifiedTab.remove(id)) pi.setDisplayName(null);
                        } else {
                            pi.setDisplayName(new ChatComponentText(text));
                            modifiedTab.add(id);
                        }
                    }
                });
            }
        });
    }

    public static void refreshAllTab() {
        List<UUID> ids;
        synchronized (LOCK) { ids = new ArrayList<UUID>(uuidToName.keySet()); }
        for (UUID id : ids) applyDisplay(id);
        for (UUID id : new ArrayList<UUID>(modifiedTab)) applyDisplay(id);
    }

    // =============================================================== spectators (proxy's check_spectators_loop)
    /** Call every ~0.5s on the client thread. */
    public static void tickSpectators() {
        final Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) return;

        Map<String, String> current = new TreeMap<String, String>();
        List<String> newlyNotified = new ArrayList<String>();
        synchronized (LOCK) {
            for (Map.Entry<UUID, String> e : uuidToName.entrySet()) {
                String name = e.getValue();
                String lower = name.toLowerCase();
                EntityPlayer ep = mc.theWorld.getPlayerEntityByUUID(e.getKey());
                Integer gm = gameModes.get(e.getKey());
                boolean isSpec = gm != null && gm.intValue() == 3;
                // a visible, non-spectator player in the world is a normal player of this game
                if (ep != null && !ep.isInvisible() && !isSpec) historicalNormal.add(lower);
                String real = resolved.get(lower);
                if (!isRealMapping(real)) real = null;
                if (isNormalPlayerLocked(name, real)) continue;                   // was in the game (living or dead)
                if (isSpec) {
                    String status = "Spectating";
                    if (ep != null) {
                        double dx = ep.posX - mc.thePlayer.posX;
                        double dy = ep.posY - mc.thePlayer.posY;
                        double dz = ep.posZ - mc.thePlayer.posZ;
                        if (Math.sqrt(dx * dx + dy * dy + dz * dz) < 0.5) status = "First Person";
                    }
                    current.put(name, status);
                }
            }
            // spectators leaked through scoreboard teams
            for (String real : spectators.values()) {
                String nick = nickFromRealLocked(real);
                if (isNormalPlayerLocked(nick != null ? nick : real, real)) continue;
                String key = nick != null ? nick : real;
                if (!current.containsKey(key)) current.put(key, "Spectating");
            }
            for (String name : current.keySet()) {
                if (notifiedSpecs.add(name.toLowerCase())) newlyNotified.add(name);
            }
            Iterator<String> it = notifiedSpecs.iterator();
            while (it.hasNext()) {
                String n = it.next();
                boolean still = false;
                for (String c : current.keySet()) if (c.equalsIgnoreCase(n)) { still = true; break; }
                if (!still) it.remove();
            }
        }

        // one chat message the first time a spectator is seen
        for (String name : newlyNotified) {
            String real;
            synchronized (LOCK) { real = resolved.get(name.toLowerCase()); }
            String shown;
            if (FAKE.equals(real)) shown = "\u00a7e" + name + " \u00a77(\u00a7c\u00a7lFAKE\u00a77)";
            else if (isRealMapping(real) && !real.equals(name)) shown = "\u00a7e" + name + " \u00a77(\u00a7b\u00a7l" + real + "\u00a77)";
            else shown = "\u00a7e" + name;
            msg0("\u00a7c\u00a7l[Spectator] " + shown + " \u00a77is spectating!");
        }

        // spectator list in the tab footer
        StringBuilder lines = new StringBuilder();
        for (Map.Entry<String, String> e : current.entrySet()) {
            String name = e.getKey();
            String real;
            synchronized (LOCK) { real = resolved.get(name.toLowerCase()); }
            String display;
            if (FAKE.equals(real)) display = name + " (FAKE)";
            else if (isRealMapping(real) && !real.equals(name)) display = name + " (" + real + ")";
            else display = name;
            if (lines.length() > 0) lines.append("\n");
            if ("First Person".equals(e.getValue())) lines.append(" \u00a7c\u00a7l").append(display).append(" \u00a77(\u00a74\u00a7l1st Person\u00a77)");
            else lines.append(" \u00a7e").append(display).append(" \u00a77(").append(e.getValue()).append(")");
        }
        String specText = current.isEmpty() ? "" : "\n\n\u00a7c\u00a7lSpectators:\n" + lines;
        String key = specText;
        if (key.equals(lastFooterKey)) return;
        lastFooterKey = key;
        IChatComponent sf = serverFooter;
        if (specText.isEmpty()) {
            if (footerModified) {
                mc.ingameGUI.getTabList().setFooter(sf);
                footerModified = false;
            }
        } else {
            IChatComponent f = sf != null ? sf.createCopy() : new ChatComponentText("");
            f.appendSibling(new ChatComponentText(specText));
            mc.ingameGUI.getTabList().setFooter(f);
            footerModified = true;
        }
    }

    /** Server sent its own tab header/footer: remember it so we can append the spectator list. */
    public static void onServerFooter(IChatComponent footer) {
        serverFooter = footer;
        lastFooterKey = null; // re-merge on the next tick
        footerModified = false;
    }

    /** Chat line that is not muted by /denick auto (used for spectator alerts like the proxy). */
    private static void msg0(String s) { post(s); }

    // proxy's reveal_uuid: mark the player whose tab UUID equals this target
    public static void setTarget(String arg) {
        if (arg == null || arg.equalsIgnoreCase("off")) {
            targetUuid = null; targetLabel = null;
            msg("Target cleared.");
            return;
        }
        UUID u = null;
        String label = arg;
        String clean = arg.replace("-", "");
        if (clean.length() == 32) {
            try {
                u = new UUID(Long.parseUnsignedLong(clean.substring(0, 16), 16), Long.parseUnsignedLong(clean.substring(16), 16));
            } catch (Exception e) { u = null; }
        }
        if (u == null && valid(arg)) u = UUID.nameUUIDFromBytes(("OfflinePlayer:" + arg).getBytes());
        if (u == null) { msg("Give a username or a 32-char UUID."); return; }
        targetUuid = u;
        targetLabel = label;
        msg("Target set: \u00a7e" + label + "\u00a7f (matched by tab UUID)");
    }

    // =============================================================== commands support
    public static List<String> listLines() {
        List<String> out = new ArrayList<String>();
        List<String> fakes = new ArrayList<String>();
        synchronized (LOCK) {
            Map<String, String> active = activeLowerLocked();
            for (Map.Entry<String, String> e : resolved.entrySet()) {
                String nick = active.containsKey(e.getKey()) ? active.get(e.getKey()) : e.getKey();
                if (isRealMapping(e.getValue())) out.add("\u00a7e" + nick + " \u00a77= \u00a7b\u00a7l" + e.getValue());
                else if (FAKE.equals(e.getValue())) fakes.add(nick);
            }
        }
        if (!fakes.isEmpty()) out.add("\u00a7cFAKE (real name unknown): \u00a7e" + fakes);
        return out;
    }

    public static void inspect(final String name) {
        String st;
        boolean tab;
        synchronized (LOCK) {
            st = resolved.get(name.toLowerCase());
            tab = activeLowerLocked().containsKey(name.toLowerCase());
        }
        if (isRealMapping(st)) {
            msg("\u00a7e" + name + " \u00a77= \u00a7b\u00a7l" + st);
        } else if (FAKE.equals(st)) {
            msg("\u00a7e" + name + " \u00a77is a \u00a7cFAKE\u00a77 nick (no Pika profile). Real name not leaked yet.");
        } else {
            msg("\u00a7e" + name + (tab ? " \u00a77is in tab; no nick detected." : " \u00a77is not in the tab list."));
        }
        if (!valid(name)) return;
        schedule(0, new Runnable() {
            public void run() {
                PikaApi.Result r = PikaApi.lookup(name);
                if (r.status == PikaApi.Status.OK) {
                    msg("Pika profile exists:" + info(r) + (PikaApi.canNick(r) ? " \u00a77| can nick" : " \u00a77| cannot nick"));
                } else if (r.status == PikaApi.Status.MISSING) {
                    msg("No Pika profile named \u00a7e" + name + "\u00a7f (404): it is not a real account.");
                } else {
                    msg("Pika API error, try again.");
                }
            }
        });
    }

    public static void dump() {
        synchronized (LOCK) {
            System.out.println("[Denicker] tab=" + uuidToName.size() + " teams=" + teams.size()
                    + " resolved=" + resolved.size() + " checked=" + checked.size());
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, String> e : uuidToName.entrySet()) {
                Long t = tabAddTimes.get(e.getValue().toLowerCase());
                System.out.println("[Denicker] TAB " + e.getKey() + " " + e.getValue()
                        + " gm=" + gameModes.get(e.getKey()) + " added " + (t == null ? "?" : (now - t.longValue()) + "ms ago"));
            }
            for (Map.Entry<String, Set<String>> e : teams.entrySet()) {
                System.out.println("[Denicker] TEAM " + e.getKey() + " members=" + e.getValue());
            }
            for (Map.Entry<String, String> e : resolved.entrySet()) {
                System.out.println("[Denicker] RESOLVED " + e.getKey() + " -> " + e.getValue());
            }
        }
    }
}

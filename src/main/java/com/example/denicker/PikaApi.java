package com.example.denicker;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pika Network stats API lookup (same endpoint the proxy uses).
 *   200 -> the account exists (a real player)
 *   404 -> no such account -> the name shown in tab is a FAKE nick
 */
public class PikaApi {
    public enum Status { OK, MISSING, ERROR }

    public static class Result {
        public final Status status;
        public final int level;
        public final List<String> ranks;
        public final String bestRank;
        public final String clan;
        public final String bestRankColored;
        public final String clanColored;

        Result(Status status, int level, List<String> ranks, String bestRank, String clan) {
            this(status, level, ranks, bestRank, clan, bestRank, clan);
        }

        Result(Status status, int level, List<String> ranks, String bestRank, String clan,
               String bestRankColored, String clanColored) {
            this.status = status;
            this.level = level;
            this.ranks = ranks;
            this.bestRank = bestRank;
            this.clan = clan;
            this.bestRankColored = bestRankColored;
            this.clanColored = clanColored;
        }
    }

    private static class Cached {
        final Result result;
        final long time;
        Cached(Result r, long t) { result = r; time = t; }
    }

    private static final Map<String, Cached> CACHE = new ConcurrentHashMap<String, Cached>();
    private static final long TTL_MS = 10L * 60L * 1000L;

    // Ranks that are allowed to use /nick (a real identity behind a nick must have one of these)
    private static final String[] NICK_RANKS = {
            "titan", "diamond", "champion", "crystal",
            "trial mod", "sr mod", "sr. mod", "trialmod", "srmod",
            "helper", "manager", "developer", "dev", "admin", "mod", "staff", "owner"
    };
    private static final String[] STAFF_RANKS = {
            "trial mod", "sr mod", "sr. mod", "trialmod", "srmod",
            "helper", "manager", "developer", "dev", "admin", "mod", "staff", "owner"
    };

    public static void clearCache() { CACHE.clear(); }

    public static Result lookup(String name) {
        if (!Engine.valid(name)) return error();
        String key = name.toLowerCase();
        long now = System.currentTimeMillis();
        Cached c = CACHE.get(key);
        if (c != null && now - c.time < TTL_MS) return c.result;
        Result r = fetch(name);
        if (r.status != Status.ERROR) CACHE.put(key, new Cached(r, now));
        return r;
    }

    private static Result error() {
        return new Result(Status.ERROR, 0, new ArrayList<String>(), "", "");
    }

    private static Result fetch(String name) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://stats.pika-network.net/api/profile/" + name);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            if (code == 404) return new Result(Status.MISSING, 0, new ArrayList<String>(), "", "");
            if (code != 200) return error();

            BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();

            JsonObject o = new JsonParser().parse(sb.toString()).getAsJsonObject();

            int level = 0;
            if (o.has("rank") && o.get("rank").isJsonObject()) {
                JsonObject ro = o.get("rank").getAsJsonObject();
                if (ro.has("level") && ro.get("level").isJsonPrimitive()) {
                    level = ro.get("level").getAsInt();
                }
            }

            List<String> ranks = new ArrayList<String>();
            String best = "";
            String bestColored = "";
            if (o.has("ranks") && o.get("ranks").isJsonArray()) {
                for (JsonElement e : o.get("ranks").getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    JsonObject ro = e.getAsJsonObject();
                    String raw = str(ro, "displayName");
                    String dn = strip(raw);
                    if (dn.isEmpty()) continue;
                    ranks.add(dn);
                    boolean global = !ro.has("server") || ro.get("server").isJsonNull();
                    if (best.isEmpty() && global) { best = dn; bestColored = raw.replace('&', '\u00a7'); }
                }
            }
            if (best.isEmpty() && !ranks.isEmpty()) { best = ranks.get(0); bestColored = best; }

            String clan = "";
            String clanColored = "";
            if (o.has("clan") && o.get("clan").isJsonObject()) {
                JsonObject co = o.get("clan").getAsJsonObject();
                String rawTag = str(co, "tag");
                String tag = strip(rawTag);
                clan = tag.isEmpty() ? str(co, "name") : tag;
                clanColored = tag.isEmpty() ? clan : rawTag.replace('&', '\u00a7');
            }
            return new Result(Status.OK, level, ranks, best, clan, bestColored, clanColored);
        } catch (Exception ex) {
            return error();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key)) return "";
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return "";
        return e.getAsString();
    }

    private static String strip(String s) {
        return s.replaceAll("[&\u00a7][0-9a-fk-orA-FK-OR]", "").trim();
    }

    private static boolean matches(Result r, String[] frags) {
        if (r == null || r.status != Status.OK) return false;
        for (String rank : r.ranks) {
            String l = rank.toLowerCase();
            for (String f : frags) if (l.contains(f)) return true;
        }
        String b = r.bestRank.toLowerCase();
        if (!b.isEmpty()) for (String f : frags) if (b.contains(f)) return true;
        return false;
    }

    public static boolean canNick(Result r) { return matches(r, NICK_RANKS); }

    public static boolean isStaff(Result r) { return matches(r, STAFF_RANKS); }
}

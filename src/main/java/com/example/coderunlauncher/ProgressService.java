package com.example.coderunlauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.*;
import java.util.*;

/**
 * Player progress in the Aiven database: wallet (coins, orbs), owned skins, upgrade levels and stats.
 *
 * Tables (created automatically when the launcher starts; existing tables are never changed):
 *   player_progress  one row per player: coins, orbs, stats, equipped skins, upgrade levels
 *   player_skins     the skins and Looks each player owns (one row per item); the Looks they wear are rows
 *                    named "@slot=id" (e.g. "@hat=hat_straw"), so no new table or permission is needed
 *   match_results    one row per finished island, for history and checking
 *
 * The launcher is the only place that writes here: the game asks (through GameBridge) and this class
 * checks prices and limits before saving, so a player cannot just give themselves coins.
 */
public final class ProgressService {

    private ProgressService() {}

    // ------------------------------------------------------------------ catalog (the source of truth for prices)

    public record Skin(String id, String name, String kind, int price) {}
    public record Upgrade(String id, String name, String column, int basePrice) {}

    public static final List<Skin> SKINS = List.of(
            new Skin("classic_wood", "Classic Wood", "boat", 0),
            new Skin("ocean_blue", "Ocean Blue", "boat", 40),
            new Skin("sunset_red", "Sunset Red", "boat", 60),
            new Skin("jungle_green", "Jungle Green", "boat", 60),
            new Skin("royal_purple", "Royal Purple", "boat", 90),
            new Skin("golden_legend", "Golden Legend", "boat", 150),
            new Skin("bamboo", "Bamboo", "rod", 0),
            new Skin("coral_pink", "Coral Pink", "rod", 30),
            new Skin("deep_sea", "Deep Sea", "rod", 45),
            new Skin("pearl_white", "Pearl White", "rod", 70),
            new Skin("starlight", "Starlight", "rod", 120));

    /**
     * The Merchant's Looks (same list as Looks.cs in the game). price 0 with minLevel above 1 = level-only.
     * orbs = paid with orbs instead of coins.
     */
    public record Look(String id, String name, String slot, int price, boolean orbs, int minLevel) {
        boolean free() { return price == 0; }
    }

    public static final List<Look> LOOKS = List.of(
            new Look("shirt_orange", "Starter Orange", "shirt", 0, false, 1),
            new Look("shirt_blue", "Ocean Blue", "shirt", 30, false, 1),
            new Look("shirt_green", "Palm Green", "shirt", 30, false, 1),
            new Look("shirt_pink", "Coral Pink", "shirt", 60, false, 1),
            new Look("shirt_red", "Sunset Red", "shirt", 60, false, 1),
            new Look("shirt_purple", "Royal Purple", "shirt", 80, false, 5),
            new Look("shirt_teal", "Storm Teal", "shirt", 120, false, 8),
            new Look("shirt_gold", "Golden Legend", "shirt", 40, true, 12),
            new Look("hair_short", "Short", "hair", 0, false, 1),
            new Look("hair_spiky", "Spiky", "hair", 40, false, 1),
            new Look("hair_long", "Long", "hair", 60, false, 1),
            new Look("hair_ponytail", "Ponytail", "hair", 60, false, 1),
            new Look("hair_curly", "Curly", "hair", 90, false, 5),
            new Look("hc_black", "Black", "haircolor", 0, false, 1),
            new Look("hc_darkbrown", "Dark Brown", "haircolor", 15, false, 1),
            new Look("hc_brown", "Brown", "haircolor", 20, false, 1),
            new Look("hc_blonde", "Blonde", "haircolor", 50, false, 1),
            new Look("hc_ginger", "Ginger", "haircolor", 50, false, 1),
            new Look("hc_blue", "Ocean Dye", "haircolor", 20, true, 8),
            new Look("hc_pink", "Bubblegum Dye", "haircolor", 20, true, 8),
            new Look("hat_cap", "Captain's Cap", "hat", 0, false, 1),
            new Look("hat_straw", "Straw Hat", "hat", 70, false, 1),
            new Look("hat_bucket", "Bucket Hat", "hat", 90, false, 5),
            new Look("hat_pirate", "Pirate Hat", "hat", 30, true, 10),
            new Look("hat_crown", "Crown", "hat", 0, false, 15),
            new Look("cel_cheer", "Cheer", "celebrate", 0, false, 1),
            new Look("cel_dance", "Dance", "celebrate", 100, false, 5),
            new Look("cel_spin", "Victory Spin", "celebrate", 30, true, 10),
            new Look("frame_plain", "Plain", "frame", 0, false, 1),
            new Look("frame_silver", "Silver Frame", "frame", 0, false, 5),
            new Look("frame_gold", "Gold Frame", "frame", 0, false, 10),
            new Look("frame_rainbow", "Rainbow Frame", "frame", 0, false, 20));

    public static Look look(String id) {
        for (Look l : LOOKS) if (l.id().equals(id)) return l;
        return null;
    }

    /** Player level, the same formula as the game (PlayerProfile.PlayerLevel): one level per 40 points. */
    public static int playerLevel(int totalScore) { return 1 + Math.max(0, totalScore) / 40; }

    public static final List<Upgrade> UPGRADES = List.of(
            new Upgrade("boat_speed", "Boat Speed", "boat_speed_lvl", 8),
            new Upgrade("boat_armor", "Boat Armor", "boat_armor_lvl", 8),
            new Upgrade("rod_bite", "Rod Bite", "rod_bite_lvl", 6),
            new Upgrade("rod_control", "Rod Control", "rod_control_lvl", 6));

    public static final int MAX_LEVEL = 5;
    public static final String DEFAULT_BOAT = "classic_wood";
    public static final String DEFAULT_ROD = "bamboo";

    // Limits for one island result (anything above this is not believable and is cut down)
    private static final int MAX_SCORE_PER_ISLAND = 80;
    private static final int MAX_COINS_PER_ISLAND = 60;
    private static final int MAX_ORBS_PER_ISLAND = 80;
    private static final int MAX_QUESTIONS_PER_ISLAND = 12;

    public static Skin skin(String id) {
        for (Skin s : SKINS) if (s.id().equals(id)) return s;
        return null;
    }

    public static Upgrade upgrade(String id) {
        for (Upgrade u : UPGRADES) if (u.id().equals(id)) return u;
        return null;
    }

    public static int upgradeCost(Upgrade u, int currentLevel) { return u.basePrice() * (currentLevel + 1); }

    // ------------------------------------------------------------------ tables

    private static volatile boolean tablesReady;

    /** Creates the three progress tables if they are missing. Safe to call many times. */
    public static synchronized void ensureTables() throws SQLException {
        if (tablesReady) return;
        try (Connection conn = DatabaseManager.getConnection(); Statement st = conn.createStatement()) {
            String progress = "CREATE TABLE IF NOT EXISTS player_progress (" +
                    "username VARCHAR(50) PRIMARY KEY, " +
                    "coins INT NOT NULL DEFAULT 0, " +
                    "orbs INT NOT NULL DEFAULT 0, " +
                    "matches_played INT NOT NULL DEFAULT 0, " +
                    "wins INT NOT NULL DEFAULT 0, " +
                    "total_score INT NOT NULL DEFAULT 0, " +
                    "questions_answered INT NOT NULL DEFAULT 0, " +
                    "questions_correct INT NOT NULL DEFAULT 0, " +
                    "boat_skin VARCHAR(40) NOT NULL DEFAULT '" + DEFAULT_BOAT + "', " +
                    "rod_skin VARCHAR(40) NOT NULL DEFAULT '" + DEFAULT_ROD + "', " +
                    "boat_speed_lvl TINYINT NOT NULL DEFAULT 0, " +
                    "boat_armor_lvl TINYINT NOT NULL DEFAULT 0, " +
                    "rod_bite_lvl TINYINT NOT NULL DEFAULT 0, " +
                    "rod_control_lvl TINYINT NOT NULL DEFAULT 0, " +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                    "INDEX idx_score (total_score)";
            String skins = "CREATE TABLE IF NOT EXISTS player_skins (" +
                    "username VARCHAR(50) NOT NULL, " +
                    "skin_id VARCHAR(40) NOT NULL, " +
                    "acquired_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "PRIMARY KEY (username, skin_id)";
            String results = "CREATE TABLE IF NOT EXISTS match_results (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "username VARCHAR(50) NOT NULL, " +
                    "island TINYINT NOT NULL, " +
                    "score INT NOT NULL, " +
                    "place_pos TINYINT NOT NULL, " +
                    "players TINYINT NOT NULL, " +
                    "coins_earned INT NOT NULL, " +
                    "orbs_earned INT NOT NULL, " +
                    "questions_answered INT NOT NULL, " +
                    "questions_correct INT NOT NULL, " +
                    "played_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                    "INDEX idx_user (username)";
            // Linked to users: renaming a player carries their progress along, deleting a player removes it.
            String fk = ", FOREIGN KEY (username) REFERENCES users(username) ON UPDATE CASCADE ON DELETE CASCADE)";
            createWithFallback(st, progress, fk);
            createWithFallback(st, skins, fk);
            createWithFallback(st, results, fk);
        }
        tablesReady = true;
    }

    /** Tries the table with the link to users; if MySQL refuses the link, creates it without. */
    private static void createWithFallback(Statement st, String body, String fk) throws SQLException {
        try {
            st.execute(body + fk);
        } catch (SQLException e) {
            if (DatabaseManager.noPermission(e)) return;   // limited player login: the tables already exist
            System.err.println("Progress table without users link (" + e.getMessage() + ")");
            st.execute(body + ")");
        }
    }

    // ------------------------------------------------------------------ reading

    /** Makes sure the player has a row (new players start at zero). */
    private static void ensureRow(Connection conn, String username) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO player_progress (username) VALUES (?)")) {
            ps.setString(1, username);
            ps.executeUpdate();
        }
    }

    /**
     * Everything the game needs, as JSON: wallet, stats, skins, upgrades, the top players and your rank.
     * Field names match PlayerProfile in the game (Unity JsonUtility).
     */
    public static JsonObject profileJson(String username) throws SQLException {
        ensureTables();
        JsonObject o = new JsonObject();
        o.addProperty("username", username);
        o.addProperty("online", true);
        try (Connection conn = DatabaseManager.getConnection()) {
            ensureRow(conn, username);
            int totalScore = 0;
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM player_progress WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        o.addProperty("coins", rs.getInt("coins"));
                        o.addProperty("orbs", rs.getInt("orbs"));
                        o.addProperty("matches", rs.getInt("matches_played"));
                        o.addProperty("wins", rs.getInt("wins"));
                        totalScore = rs.getInt("total_score");
                        o.addProperty("totalScore", totalScore);
                        o.addProperty("answered", rs.getInt("questions_answered"));
                        o.addProperty("correct", rs.getInt("questions_correct"));
                        o.addProperty("boatSkin", rs.getString("boat_skin"));
                        o.addProperty("rodSkin", rs.getString("rod_skin"));
                        JsonArray ups = new JsonArray();
                        for (Upgrade u : UPGRADES) {
                            JsonObject uo = new JsonObject();
                            uo.addProperty("id", u.id());
                            uo.addProperty("level", rs.getInt(u.column()));
                            ups.add(uo);
                        }
                        o.add("upgrades", ups);
                    }
                }
            }
            JsonArray owned = new JsonArray();
            JsonArray wearing = new JsonArray();
            owned.add(DEFAULT_BOAT);
            owned.add(DEFAULT_ROD);
            try (PreparedStatement ps = conn.prepareStatement("SELECT skin_id FROM player_skins WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString(1);
                        if (id.startsWith("@")) wearing.add(id.substring(1));   // "@hat=hat_straw" -> "hat=hat_straw"
                        else owned.add(id);
                    }
                }
            }
            o.add("ownedSkins", owned);
            o.add("equippedLooks", wearing);

            JsonArray board = new JsonArray();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT username, total_score, wins FROM player_progress " +
                         "ORDER BY total_score DESC, wins DESC, username LIMIT 10")) {
                while (rs.next()) {
                    JsonObject row = new JsonObject();
                    row.addProperty("name", rs.getString(1));
                    row.addProperty("score", rs.getInt(2));
                    row.addProperty("wins", rs.getInt(3));
                    board.add(row);
                }
            }
            o.add("leaderboard", board);
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) + 1 FROM player_progress WHERE total_score > ?")) {
                ps.setInt(1, totalScore);
                try (ResultSet rs = ps.executeQuery()) {
                    o.addProperty("rank", rs.next() ? rs.getInt(1) : 0);
                }
            }
        }
        return o;
    }

    // ------------------------------------------------------------------ changes requested by the game

    /** Applies one request from the game. Returns a short message for the player. */
    public static String apply(String username, JsonObject evt) throws SQLException {
        ensureTables();
        String type = str(evt, "type");
        try (Connection conn = DatabaseManager.getConnection()) {
            ensureRow(conn, username);
            conn.setAutoCommit(false);
            try {
                String msg = switch (type) {
                    case "island_result" -> islandResult(conn, username, evt);
                    case "buy" -> buy(conn, username, str(evt, "id"));
                    case "equip" -> equip(conn, username, str(evt, "id"));
                    case "upgrade" -> doUpgrade(conn, username, str(evt, "id"));
                    case "refresh" -> "";
                    default -> "";
                };
                conn.commit();
                return msg;
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private static String islandResult(Connection conn, String user, JsonObject e) throws SQLException {
        int island = clamp(num(e, "island"), 1, 3);
        int score = clamp(num(e, "score"), 0, MAX_SCORE_PER_ISLAND);
        int players = clamp(num(e, "players"), 1, 8);
        int place = clamp(num(e, "place"), 1, players);
        int coins = clamp(num(e, "coins"), 0, MAX_COINS_PER_ISLAND);
        int orbs = clamp(num(e, "orbs"), 0, MAX_ORBS_PER_ISLAND);
        int answered = clamp(num(e, "answered"), 0, MAX_QUESTIONS_PER_ISLAND);
        int correct = clamp(num(e, "correct"), 0, answered);

        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE player_progress SET coins = coins + ?, orbs = orbs + ?, total_score = total_score + ?, " +
                "questions_answered = questions_answered + ?, questions_correct = questions_correct + ?, " +
                "matches_played = matches_played + ?, wins = wins + ? WHERE username = ?")) {
            ps.setInt(1, coins);
            ps.setInt(2, orbs);
            ps.setInt(3, score);
            ps.setInt(4, answered);
            ps.setInt(5, correct);
            ps.setInt(6, island == 1 ? 1 : 0);        // a match starts on Island 1
            ps.setInt(7, place == 1 && players > 1 ? 1 : 0);
            ps.setString(8, user);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO match_results (username, island, score, place_pos, players, coins_earned, orbs_earned, " +
                "questions_answered, questions_correct) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, user);
            ps.setInt(2, island);
            ps.setInt(3, score);
            ps.setInt(4, place);
            ps.setInt(5, players);
            ps.setInt(6, coins);
            ps.setInt(7, orbs);
            ps.setInt(8, answered);
            ps.setInt(9, correct);
            ps.executeUpdate();
        }
        StringBuilder sb = new StringBuilder("Saved: +" + score + " pts");
        if (coins > 0) sb.append(", +").append(coins).append(" coins");
        if (orbs > 0) sb.append(", +").append(orbs).append(" orbs");
        return sb.toString();
    }

    private static int[] wallet(Connection conn, String user) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT coins, orbs FROM player_progress WHERE username = ? FOR UPDATE")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new int[]{rs.getInt(1), rs.getInt(2)} : new int[]{0, 0};
            }
        }
    }

    private static boolean owns(Connection conn, String user, Skin s) throws SQLException {
        if (s.price() == 0) return true;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM player_skins WHERE username = ? AND skin_id = ?")) {
            ps.setString(1, user);
            ps.setString(2, s.id());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static int totalScore(Connection conn, String user) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT total_score FROM player_progress WHERE username = ?")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }

    private static boolean ownsLook(Connection conn, String user, Look l) throws SQLException {
        if (playerLevel(totalScore(conn, user)) < l.minLevel()) return false;
        if (l.free()) return true;
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM player_skins WHERE username = ? AND skin_id = ?")) {
            ps.setString(1, user);
            ps.setString(2, l.id());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static String buyLook(Connection conn, String user, Look l) throws SQLException {
        if (ownsLook(conn, user, l)) return equipLook(conn, user, l);
        int lvl = playerLevel(totalScore(conn, user));
        if (lvl < l.minLevel()) return l.name() + " unlocks at level " + l.minLevel() + " (you are level " + lvl + ").";
        int[] w = wallet(conn, user);
        int have = l.orbs() ? w[1] : w[0];
        String cur = l.orbs() ? "orbs" : "coins";
        if (have < l.price()) return "Not enough " + cur + " - you need " + l.price() + ".";
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE player_progress SET " + (l.orbs() ? "orbs = orbs" : "coins = coins") + " - ? WHERE username = ?")) {
            ps.setInt(1, l.price());
            ps.setString(2, user);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO player_skins (username, skin_id) VALUES (?, ?)")) {
            ps.setString(1, user);
            ps.setString(2, l.id());
            ps.executeUpdate();
        }
        wearLook(conn, user, l);
        return "Bought " + l.name() + "!";
    }

    private static String equipLook(Connection conn, String user, Look l) throws SQLException {
        if (!ownsLook(conn, user, l)) return "You don't have " + l.name() + " yet.";
        wearLook(conn, user, l);
        return "Wearing " + l.name() + ".";
    }

    private static void wearLook(Connection conn, String user, Look l) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM player_skins WHERE username = ? AND skin_id LIKE ?")) {
            ps.setString(1, user);
            ps.setString(2, "@" + l.slot() + "=%");
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO player_skins (username, skin_id) VALUES (?, ?)")) {
            ps.setString(1, user);
            ps.setString(2, "@" + l.slot() + "=" + l.id());
            ps.executeUpdate();
        }
    }

    private static String buy(Connection conn, String user, String id) throws SQLException {
        Look l = look(id);
        if (l != null) return buyLook(conn, user, l);
        Skin s = skin(id);
        if (s == null) return "That item is not for sale.";
        if (owns(conn, user, s)) return equip(conn, user, id);
        int coins = wallet(conn, user)[0];
        if (coins < s.price()) return "Not enough coins - you need " + s.price() + ".";
        try (PreparedStatement ps = conn.prepareStatement("UPDATE player_progress SET coins = coins - ? WHERE username = ?")) {
            ps.setInt(1, s.price());
            ps.setString(2, user);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO player_skins (username, skin_id) VALUES (?, ?)")) {
            ps.setString(1, user);
            ps.setString(2, s.id());
            ps.executeUpdate();
        }
        setEquipped(conn, user, s);
        return "Bought " + s.name() + "!";
    }

    private static String equip(Connection conn, String user, String id) throws SQLException {
        Look l = look(id);
        if (l != null) return equipLook(conn, user, l);
        Skin s = skin(id);
        if (s == null) return "Unknown skin.";
        if (!owns(conn, user, s)) return "You don't own " + s.name() + " yet.";
        setEquipped(conn, user, s);
        return "Equipped " + s.name() + ".";
    }

    private static void setEquipped(Connection conn, String user, Skin s) throws SQLException {
        String col = "boat".equals(s.kind()) ? "boat_skin" : "rod_skin";
        try (PreparedStatement ps = conn.prepareStatement("UPDATE player_progress SET " + col + " = ? WHERE username = ?")) {
            ps.setString(1, s.id());
            ps.setString(2, user);
            ps.executeUpdate();
        }
    }

    private static String doUpgrade(Connection conn, String user, String id) throws SQLException {
        Upgrade u = upgrade(id);
        if (u == null) return "Unknown upgrade.";
        int level;
        try (PreparedStatement ps = conn.prepareStatement("SELECT " + u.column() + " FROM player_progress WHERE username = ? FOR UPDATE")) {
            ps.setString(1, user);
            try (ResultSet rs = ps.executeQuery()) { level = rs.next() ? rs.getInt(1) : 0; }
        }
        if (level >= MAX_LEVEL) return u.name() + " is already at max level.";
        int cost = upgradeCost(u, level);
        int orbs = wallet(conn, user)[1];
        if (orbs < cost) return "Not enough orbs - you need " + cost + ".";
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE player_progress SET orbs = orbs - ?, " + u.column() + " = " + u.column() + " + 1 WHERE username = ?")) {
            ps.setInt(1, cost);
            ps.setString(2, user);
            ps.executeUpdate();
        }
        return u.name() + " upgraded to level " + (level + 1) + "!";
    }

    // ------------------------------------------------------------------ admin

    /** One player's progress for the admin screen (missing players come back as all zeros). */
    public static Map<String, Object> adminLoad(String username) throws SQLException {
        ensureTables();
        Map<String, Object> m = new LinkedHashMap<>();
        try (Connection conn = DatabaseManager.getConnection()) {
            ensureRow(conn, username);
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM player_progress WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        for (String c : ADMIN_INT_COLUMNS) m.put(c, rs.getInt(c));
                        m.put("boat_skin", rs.getString("boat_skin"));
                        m.put("rod_skin", rs.getString("rod_skin"));
                    }
                }
            }
            Set<String> owned = new TreeSet<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT skin_id FROM player_skins WHERE username = ?")) {
                ps.setString(1, username);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) owned.add(rs.getString(1)); }
            }
            m.put("owned", owned);
        }
        return m;
    }

    /** Number columns the admin can edit, in display order. */
    public static final List<String> ADMIN_INT_COLUMNS = List.of(
            "coins", "orbs", "matches_played", "wins", "total_score", "questions_answered", "questions_correct",
            "boat_speed_lvl", "boat_armor_lvl", "rod_bite_lvl", "rod_control_lvl");

    /** Saves the admin's edits for one player (numbers, equipped skins and the owned-skin list). */
    public static void adminSave(String username, Map<String, Integer> numbers, String boatSkin, String rodSkin,
                                 Set<String> owned) throws SQLException {
        ensureTables();
        try (Connection conn = DatabaseManager.getConnection()) {
            ensureRow(conn, username);
            conn.setAutoCommit(false);
            try {
                StringBuilder sql = new StringBuilder("UPDATE player_progress SET ");
                for (String c : ADMIN_INT_COLUMNS) sql.append(c).append(" = ?, ");
                sql.append("boat_skin = ?, rod_skin = ? WHERE username = ?");
                try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    int i = 1;
                    for (String c : ADMIN_INT_COLUMNS) {
                        int v = Math.max(0, numbers.getOrDefault(c, 0));
                        if (c.endsWith("_lvl")) v = Math.min(MAX_LEVEL, v);
                        ps.setInt(i++, v);
                    }
                    ps.setString(i++, boatSkin);
                    ps.setString(i++, rodSkin);
                    ps.setString(i, username);
                    ps.executeUpdate();
                }
                // only the boat and rod skins are edited here; bought Looks and what the player wears stay
                try (PreparedStatement del = conn.prepareStatement("DELETE FROM player_skins WHERE username = ? AND skin_id = ?")) {
                    for (Skin s : SKINS) {
                        del.setString(1, username);
                        del.setString(2, s.id());
                        del.addBatch();
                    }
                    del.executeBatch();
                }
                try (PreparedStatement ins = conn.prepareStatement("INSERT INTO player_skins (username, skin_id) VALUES (?, ?)")) {
                    for (String id : owned) {
                        Skin s = skin(id);
                        if (s == null || s.price() == 0) continue;
                        ins.setString(1, username);
                        ins.setString(2, id);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    /** Puts one player back to a fresh start (keeps the account itself). */
    public static void adminReset(String username) throws SQLException {
        ensureTables();
        try (Connection conn = DatabaseManager.getConnection()) {
            try (PreparedStatement a = conn.prepareStatement("DELETE FROM player_skins WHERE username = ?");
                 PreparedStatement b = conn.prepareStatement("DELETE FROM player_progress WHERE username = ?")) {
                a.setString(1, username);
                a.executeUpdate();
                b.setString(1, username);
                b.executeUpdate();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    private static int num(JsonObject o, String k) {
        try { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0; }
        catch (Exception e) { return 0; }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}

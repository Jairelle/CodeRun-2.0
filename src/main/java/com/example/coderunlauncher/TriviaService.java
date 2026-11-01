package com.example.coderunlauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Fills the Aiven {@code trivia_questions} table from Open Trivia DB (https://opentdb.com)
 * and exports a random set of questions for the Unity client.
 *
 * Open Trivia DB data is licensed CC BY-SA 4.0 - credit "Open Trivia DB" somewhere in the game.
 * Rate limit: 1 request per 5 seconds per IP, so fetching is done sequentially with a pause.
 */
public final class TriviaService {

    // Open Trivia DB category ids
    public static final int CAT_GENERAL = 9;     // General Knowledge
    public static final int CAT_COMPUTERS = 18;  // Science: Computers
    public static final int CAT_MATH = 19;       // Science: Mathematics

    private static final int[] DEFAULT_CATEGORIES = { CAT_COMPUTERS, CAT_GENERAL, CAT_MATH };
    private static final long RATE_LIMIT_MS = 5500;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Only one fetch at a time, so we never break the 1-request-per-5-seconds rule. */
    private static final Object FETCH_LOCK = new Object();

    private TriviaService() {}

    // ---------------------------------------------------------------- table

    public static void ensureTable() throws SQLException {
        String sql = "CREATE TABLE IF NOT EXISTS trivia_questions (" +
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                "question VARCHAR(500) NOT NULL, " +
                "option_a VARCHAR(255) NOT NULL, " +
                "option_b VARCHAR(255) NOT NULL, " +
                "option_c VARCHAR(255) NOT NULL, " +
                "option_d VARCHAR(255) NOT NULL, " +
                "correct_index TINYINT NOT NULL, " +
                "category VARCHAR(100) NOT NULL, " +
                "difficulty VARCHAR(10) NOT NULL, " +
                "source VARCHAR(20) NOT NULL DEFAULT 'opentdb', " +
                "question_hash CHAR(64) NOT NULL UNIQUE, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)";
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (SQLException e) {
            if (!DatabaseManager.noPermission(e)) throw e;   // limited player login: the table already exists
        }
    }

    public static int count() throws SQLException {
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM trivia_questions")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // ---------------------------------------------------------------- fetching

    /**
     * Makes sure the table exists and holds at least {@code minimum} questions, fetching
     * from Open Trivia DB if it does not. Slow (several seconds) - call from a background thread.
     *
     * @return how many new questions were added
     */
    public static int topUp(int minimum) throws IOException, InterruptedException, SQLException {
        synchronized (FETCH_LOCK) {
            ensureTable();
            if (count() >= minimum) return 0;

            int added = 0;
            for (int category : DEFAULT_CATEGORIES) {
                added += fetchAndStore(category, null);
                if (count() >= minimum) break;
                Thread.sleep(RATE_LIMIT_MS);
            }
            return added;
        }
    }

    /**
     * Fetches up to 50 multiple-choice questions for one category and stores the new ones.
     * Questions already in the table (same text) are skipped.
     *
     * @param difficulty "easy", "medium", "hard" or null for any
     * @return how many new questions were stored
     */
    public static int fetchAndStore(int categoryId, String difficulty)
            throws IOException, InterruptedException, SQLException {
        StringBuilder url = new StringBuilder("https://opentdb.com/api.php?amount=50&type=multiple")
                .append("&encode=url3986")   // percent-encoded text, so no HTML entities like &quot;
                .append("&category=").append(categoryId);
        if (difficulty != null) url.append("&difficulty=").append(difficulty);

        JsonObject root = getJson(url.toString());
        int code = root.get("response_code").getAsInt();
        if (code == 5) { // rate limited: wait once and retry
            Thread.sleep(RATE_LIMIT_MS + 1000);
            root = getJson(url.toString());
            code = root.get("response_code").getAsInt();
        }
        if (code != 0) return 0; // 1 = no results, anything else = nothing usable

        String sql = "INSERT IGNORE INTO trivia_questions " +
                "(question, option_a, option_b, option_c, option_d, correct_index, category, difficulty, question_hash) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        int added = 0;
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (JsonElement element : root.getAsJsonArray("results")) {
                JsonObject q = element.getAsJsonObject();

                String question = decode(q.get("question").getAsString());
                String correct = decode(q.get("correct_answer").getAsString());
                List<String> options = new ArrayList<>();
                for (JsonElement wrong : q.getAsJsonArray("incorrect_answers")) {
                    options.add(decode(wrong.getAsString()));
                }
                if (options.size() != 3) continue;          // multiple choice must have 4 options in total

                options.add(correct);
                Collections.shuffle(options);               // the API always lists the right answer separately
                int correctIndex = options.indexOf(correct);

                if (question.length() > 500) continue;
                boolean tooLong = false;
                for (String o : options) if (o.length() > 255) tooLong = true;
                if (tooLong) continue;

                ps.setString(1, question);
                ps.setString(2, options.get(0));
                ps.setString(3, options.get(1));
                ps.setString(4, options.get(2));
                ps.setString(5, options.get(3));
                ps.setInt(6, correctIndex);
                ps.setString(7, decode(q.get("category").getAsString()));
                ps.setString(8, q.get("difficulty").getAsString());
                ps.setString(9, sha256(question.toLowerCase(Locale.ROOT)));
                added += ps.executeUpdate();                // 0 when it is a duplicate
            }
        }
        return added;
    }

    // ---------------------------------------------------------------- export for Unity

    /**
     * Writes {@code count} random questions to {@code file} as JSON for the Unity client:
     * {"questions":[{"question":"...","options":["a","b","c","d"],"correctIndex":2,"category":"..."}]}
     *
     * @return how many questions were written
     */
    public static int exportRandom(int count, Path file) throws SQLException, IOException {
        JsonArray questions = new JsonArray();
        String sql = "SELECT question, option_a, option_b, option_c, option_d, correct_index, category " +
                "FROM trivia_questions ORDER BY RAND() LIMIT ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, count);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject q = new JsonObject();
                    q.addProperty("question", rs.getString("question"));
                    JsonArray options = new JsonArray();
                    options.add(rs.getString("option_a"));
                    options.add(rs.getString("option_b"));
                    options.add(rs.getString("option_c"));
                    options.add(rs.getString("option_d"));
                    q.add("options", options);
                    q.addProperty("correctIndex", rs.getInt("correct_index"));
                    q.addProperty("category", rs.getString("category"));
                    questions.add(q);
                }
            }
        }
        if (questions.isEmpty()) return 0;

        JsonObject root = new JsonObject();
        root.add("questions", questions);
        Path absolute = file.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Files.writeString(absolute, root.toString(), StandardCharsets.UTF_8);
        return questions.size();
    }

    // ---------------------------------------------------------------- helpers

    private static JsonObject getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("Open Trivia DB returned HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8).trim();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e); // every JVM has SHA-256
        }
    }
}

package com.example.coderunlauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The game's own questions: every island has one topic, and an admin writes the questions in the
 * "Island Questions" editor (QuestionAdminWindow).
 *
 *   Island 1 - Trivia Arena    : Information Management (databases)
 *   Island 2 - Dream Lake      : Data Structures
 *   Island 3 - Lighthouse Run  : Object-Oriented Programming
 *
 * Each island has its own table: island1_questions, island2_questions, island3_questions
 * (id, question, option_a..option_d, correct_index 0-3, question_hash, created_at).
 * The first time this version runs, the island questions from the old shared trivia_questions table
 * (source = 'custom') are copied into the island tables; trivia_questions itself is left as it was.
 *
 * The topic names must match TriviaBank.cs in the Unity project.
 */
public final class IslandQuestions {
    // Topic names must match TriviaBank.cs in the Unity project
    public static final String[] TOPICS = { "Info Management", "Data Structures", "OOP" };
    public static final String[] ISLANDS = { "Island 1 - Trivia Arena", "Island 2 - Dream Lake", "Island 3 - Lighthouse Run" };
    public static final String[] TOPIC_LONG = { "Information Management (databases)", "Data Structures", "Object-Oriented Programming" };
    /** Below this many questions for a topic, the game tops the island up with its built-in starter questions. */
    public static final int MIN_PER_TOPIC = 10;

    private static final String SOURCE = "custom";

    /** The table that holds an island's questions (index 0-2). */
    public static String table(int island) { return "island" + (island + 1) + "_questions"; }

    private static String tableFor(String topic) {
        int i = indexOf(topic);
        if (i < 0) throw new IllegalArgumentException("Unknown island topic: " + topic);
        return table(i);
    }

    private static volatile boolean ready;

    /** Creates the three island tables and copies the old island questions in, once. */
    public static synchronized void ensureTables() throws SQLException {
        if (ready) return;
        TriviaService.ensureTable();
        try (Connection conn = DatabaseManager.getConnection(); Statement st = conn.createStatement()) {
            for (int i = 0; i < TOPICS.length; i++) {
                try {
                    st.execute("CREATE TABLE IF NOT EXISTS " + table(i) + " (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, " +
                            "question VARCHAR(500) NOT NULL, " +
                            "option_a VARCHAR(255) NOT NULL, " +
                            "option_b VARCHAR(255) NOT NULL, " +
                            "option_c VARCHAR(255) NOT NULL, " +
                            "option_d VARCHAR(255) NOT NULL, " +
                            "correct_index TINYINT NOT NULL, " +
                            "question_hash CHAR(64) NOT NULL UNIQUE, " +
                            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
                } catch (SQLException e) {
                    if (!DatabaseManager.noPermission(e)) throw e;   // limited login: the tables already exist
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table(i))) {
                    if (rs.next() && rs.getInt(1) > 0) continue;
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT IGNORE INTO " + table(i) +
                        " (question, option_a, option_b, option_c, option_d, correct_index, question_hash) " +
                        "SELECT question, option_a, option_b, option_c, option_d, correct_index, question_hash " +
                        "FROM trivia_questions WHERE source = ? AND category = ? ORDER BY id")) {
                    ps.setString(1, SOURCE);
                    ps.setString(2, TOPICS[i]);
                    ps.executeUpdate();
                } catch (SQLException e) {
                    if (!DatabaseManager.noPermission(e)) System.err.println("Could not copy old " + TOPICS[i] + " questions: " + e.getMessage());
                }
            }
        }
        ready = true;
    }

    public record Question(int id, String topic, String question, String[] options, int correct) {
        @Override public String toString() { return question; }
    }

    private IslandQuestions() {}

    // ---------------------------------------------------------------- read

    /** All questions of one topic, oldest first. */
    public static List<Question> list(String topic) throws SQLException {
        List<Question> out = new ArrayList<>();
        ensureTables();
        String sql = "SELECT id, question, option_a, option_b, option_c, option_d, correct_index FROM " + tableFor(topic) + " ORDER BY id";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(read(rs, topic));
        }
        return out;
    }

    private static Question read(ResultSet rs, String topic) throws SQLException {
        return new Question(rs.getInt("id"), topic, rs.getString("question"),
                new String[] { rs.getString("option_a"), rs.getString("option_b"), rs.getString("option_c"), rs.getString("option_d") },
                rs.getInt("correct_index"));
    }

    // ---------------------------------------------------------------- write

    /** Checks a question before saving. Returns a message for the admin, or null if it is fine. */
    public static String problem(Question q) {
        if (q.topic() == null || indexOf(q.topic()) < 0) return "Pick an island.";
        if (q.question() == null || q.question().isBlank()) return "Type the question.";
        if (q.question().length() > 500) return "The question is too long (500 characters at most).";
        if (q.options() == null || q.options().length != 4) return "A question needs 4 answers.";
        for (int i = 0; i < 4; i++) {
            String o = q.options()[i];
            if (o == null || o.isBlank()) return "Fill in answer " + (char) ('A' + i) + ".";
            if (o.length() > 255) return "Answer " + (char) ('A' + i) + " is too long (255 characters at most).";
            for (int j = 0; j < i; j++)
                if (o.trim().equalsIgnoreCase(q.options()[j].trim())) return "Answers " + (char) ('A' + j) + " and " + (char) ('A' + i) + " are the same.";
        }
        if (q.correct() < 0 || q.correct() > 3) return "Mark which answer is correct.";
        return null;
    }

    /** Adds a new question. Throws SQLException (duplicate entry) if the same question already exists for the topic. */
    public static void add(Question q) throws SQLException {
        ensureTables();
        String sql = "INSERT INTO " + tableFor(q.topic()) +
                " (question, option_a, option_b, option_c, option_d, correct_index, question_hash) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            fill(ps, q);
            ps.setString(7, hash(q));
            ps.executeUpdate();
        }
    }

    public static void update(Question q) throws SQLException {
        ensureTables();
        String sql = "UPDATE " + tableFor(q.topic()) + " SET question = ?, option_a = ?, option_b = ?, option_c = ?, option_d = ?, " +
                "correct_index = ?, question_hash = ? WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            fill(ps, q);
            ps.setString(7, hash(q));
            ps.setInt(8, q.id());
            ps.executeUpdate();
        }
    }

    public static void delete(String topic, int id) throws SQLException {
        ensureTables();
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM " + tableFor(topic) + " WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    private static void fill(PreparedStatement ps, Question q) throws SQLException {
        ps.setString(1, q.question().trim());
        for (int i = 0; i < 4; i++) ps.setString(2 + i, q.options()[i].trim());
        ps.setInt(6, q.correct());
    }

    /** True if this SQL error means "that question is already in the table". */
    public static boolean isDuplicate(SQLException e) {
        return e.getErrorCode() == 1062 || "23000".equals(e.getSQLState());
    }

    // ---------------------------------------------------------------- starter questions

    /**
     * First run: if there are no custom questions at all, puts the starter set (15 basic questions per island)
     * in the table so the admin has something to edit. Does nothing once any custom question exists.
     *
     * @return how many questions were added
     */
    public static int seedStarterIfEmpty() throws SQLException {
        ensureTables();
        int added = 0;
        try (Connection conn = DatabaseManager.getConnection(); Statement st = conn.createStatement()) {
            for (int t = 0; t < TOPICS.length; t++) {
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table(t))) {
                    if (rs.next() && rs.getInt(1) > 0) continue;
                }
                String sql = "INSERT IGNORE INTO " + table(t) +
                        " (question, option_a, option_b, option_c, option_d, correct_index, question_hash) VALUES (?, ?, ?, ?, ?, ?, ?)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (String[] s : STARTER) {
                        if (Integer.parseInt(s[0]) != t) continue;
                        Question q = new Question(0, TOPICS[t], s[1], new String[] { s[2], s[3], s[4], s[5] }, Integer.parseInt(s[6]));
                        fill(ps, q);
                        ps.setString(7, hash(q));
                        added += ps.executeUpdate();
                    }
                }
            }
        }
        return added;
    }

    // ---------------------------------------------------------------- export for Unity

    /**
     * Writes every island question to {@code file} as JSON for the Unity client:
     * {"questions":[{"question":"...","options":["a","b","c","d"],"correctIndex":2,"category":"OOP"}]}
     * Always ALL of them, in id order - every player in an online match must have the same list, because
     * the match seed picks questions by position.
     *
     * @return how many questions were written
     */
    public static int export(Path file) throws SQLException, IOException {
        ensureTables();
        JsonArray questions = new JsonArray();
        try (Connection conn = DatabaseManager.getConnection()) {
            for (int t = 0; t < TOPICS.length; t++) {
                String sql = "SELECT id, question, option_a, option_b, option_c, option_d, correct_index FROM " + table(t) + " ORDER BY id";
                try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                  while (rs.next()) {
                    Question q = read(rs, TOPICS[t]);
                    JsonObject o = new JsonObject();
                    o.addProperty("question", q.question());
                    JsonArray options = new JsonArray();
                    for (String opt : q.options()) options.add(opt);
                    o.add("options", options);
                    o.addProperty("correctIndex", q.correct());
                    o.addProperty("category", q.topic());
                    questions.add(o);
                  }
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

    public static int indexOf(String topic) {
        for (int i = 0; i < TOPICS.length; i++) if (TOPICS[i].equals(topic)) return i;
        return -1;
    }

    /** The table needs a unique hash per question: topic + question text (so the same wording can't be added twice). */
    private static String hash(Question q) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String text = "custom|" + q.topic() + "|" + q.question().trim().toLowerCase();
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // { topic index, question, A, B, C, D, correct index } - the same starter set is built into the game (TriviaBank.cs)
    private static final String[][] STARTER = {
        { "0", "What is a database?", "A type of computer virus", "An organized collection of data", "A programming language", "A kind of keyboard", "1" },
        { "0", "Which software is used to create and manage databases?", "DBMS", "Web browser", "Antivirus", "Media player", "0" },
        { "0", "In a database table, what is a row also called?", "Field", "Key", "Record", "Query", "2" },
        { "0", "In a database table, what is a column also called?", "Record", "Field", "Table", "Index", "1" },
        { "0", "What does a primary key do?", "Deletes old records", "Sorts the table", "Locks the database", "Uniquely identifies each record", "3" },
        { "0", "What does SQL stand for?", "Structured Query Language", "Simple Question List", "System Quality Level", "Stored Query Logic", "0" },
        { "0", "Which SQL command gets data from a table?", "INSERT", "DELETE", "SELECT", "UPDATE", "2" },
        { "0", "Which SQL command adds a new row to a table?", "INSERT", "SELECT", "DROP", "WHERE", "0" },
        { "0", "Which SQL command changes data that is already in a table?", "SELECT", "CREATE", "INSERT", "UPDATE", "3" },
        { "0", "Which SQL command removes rows from a table?", "REMOVE", "DELETE", "CLEAR", "SELECT", "1" },
        { "0", "Which key connects one table to another table?", "Primary key", "Space key", "Foreign key", "Shift key", "2" },
        { "0", "Which of these is a database management system?", "Google Chrome", "Paint", "Microsoft Word", "MySQL", "3" },
        { "0", "Which SQL clause is used to filter rows?", "WHERE", "FROM", "ORDER", "TABLE", "0" },
        { "0", "Raw facts and figures that are not yet processed are called...", "Information", "Data", "Knowledge", "Software", "1" },
        { "0", "Data that has been processed so it is useful is called...", "Hardware", "A table", "Information", "A key", "2" },
        { "1", "Which data structure works as First-In-First-Out (FIFO)?", "Stack", "Queue", "Tree", "Array", "1" },
        { "1", "Which data structure works as Last-In-First-Out (LIFO)?", "Stack", "Queue", "Graph", "Linked list", "0" },
        { "1", "Adding an item to the top of a stack is called...", "Pop", "Enqueue", "Push", "Peek", "2" },
        { "1", "Removing the top item from a stack is called...", "Push", "Insert", "Enqueue", "Pop", "3" },
        { "1", "In most programming languages, what is the index of the first item in an array?", "0", "1", "-1", "10", "0" },
        { "1", "What is an array?", "A single number", "A list of items stored in order and accessed by index", "A type of loop", "A kind of computer", "1" },
        { "1", "In a linked list, each node holds data and...", "a password", "a picture", "a link to the next node", "nothing else", "2" },
        { "1", "What is the top node of a tree called?", "Leaf", "Branch", "Stem", "Root", "3" },
        { "1", "A tree node that has no children is called a...", "Leaf", "Root", "Parent", "Queue", "0" },
        { "1", "In a binary tree, each node can have at most how many children?", "1", "2", "3", "4", "1" },
        { "1", "Adding an item to the back of a queue is called...", "Push", "Pop", "Enqueue", "Sort", "2" },
        { "1", "Which data structure is used by the Undo button?", "Queue", "Tree", "Graph", "Stack", "3" },
        { "1", "Which data structure is like people waiting in line?", "Queue", "Stack", "Tree", "Array", "0" },
        { "1", "A graph is made of...", "rows and columns", "nodes and edges", "keys and locks", "loops and ifs", "1" },
        { "1", "Which search checks the items one by one from the start?", "Binary search", "Quick search", "Linear search", "Jump search", "2" },
        { "2", "What does OOP stand for?", "Object-Oriented Programming", "Open Online Program", "Order Of Process", "Only One Program", "0" },
        { "2", "What is a class?", "A running program", "A blueprint for creating objects", "A type of loop", "A kind of error", "1" },
        { "2", "What is an object?", "A comment in the code", "A kind of file", "An instance of a class", "A keyboard shortcut", "2" },
        { "2", "Which keyword creates a new object in Java?", "make", "create", "object", "new", "3" },
        { "2", "Hiding data inside a class and using methods to access it is called...", "Encapsulation", "Inheritance", "Compilation", "Iteration", "0" },
        { "2", "When a class gets the properties and methods of another class, it is called...", "Encapsulation", "Inheritance", "Abstraction", "Looping", "1" },
        { "2", "Which Java keyword is used for inheritance?", "inherits", "import", "extends", "super", "2" },
        { "2", "Which OOP idea means 'many forms' - the same method acting differently for different objects?", "Abstraction", "Encapsulation", "Inheritance", "Polymorphism", "3" },
        { "2", "Showing only the important details and hiding the complex parts is called...", "Abstraction", "Inheritance", "Recursion", "Casting", "0" },
        { "2", "What is the special method that runs when an object is created?", "Destructor", "Constructor", "Getter", "Main", "1" },
        { "2", "A function that belongs to a class is called a...", "Variable", "Package", "Method", "Comment", "2" },
        { "2", "A variable that belongs to an object, like a car's color, is called...", "A loop", "A method", "A compiler", "An attribute", "3" },
        { "2", "Which access modifier lets a member be used only inside its own class?", "private", "public", "static", "open", "0" },
        { "2", "Which access modifier lets a member be used from anywhere?", "private", "public", "hidden", "local", "1" },
        { "2", "The class that another class inherits from is called the...", "Child class", "Main class", "Parent class", "Final class", "2" },
    };
}

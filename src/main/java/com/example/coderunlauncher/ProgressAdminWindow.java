package com.example.coderunlauncher;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.sql.*;
import java.util.*;

/**
 * Admin window: pick a player on the left (big, easy-to-click cards) and edit their saved progress on the
 * right - wallet, stats, upgrade levels and skins - with +/- buttons. Opened from the Admin dashboard's
 * "Player Progress" button. Nothing is written until "Save changes" is pressed.
 */
public final class ProgressAdminWindow {

    private static final String ERR = "#ff6b6b";
    private static final String OK = "#7dffb0";

    // colours from the launcher theme
    private static final String WOOD = "#f0d49b", TEAL = "#2b8a8e", DARK_TEAL = "#1f6568", INK = "#3b2314",
            CREAM = "#f3e4c0", YELLOW = "#f6d96b", PANEL = "rgba(22,38,46,0.92)";

    private record PlayerRow(String name, int coins, int orbs, int score) {}

    private final Stage stage = new Stage();
    private final List<PlayerRow> allPlayers = new ArrayList<>();
    private final ListView<PlayerRow> players = new ListView<>();
    private final Map<String, TextField> numbers = new LinkedHashMap<>();
    private final Map<String, ToggleButton> ownedChips = new LinkedHashMap<>();
    private final ComboBox<ProgressService.Skin> boatSkin = new ComboBox<>();
    private final ComboBox<ProgressService.Skin> rodSkin = new ComboBox<>();
    private final Label title = new Label("Pick a player");
    private final Label dirtyLabel = new Label();
    private final Label status = new Label();
    private final VBox editor = new VBox(14);
    private String current;
    private boolean dirty, loading;

    public static void open(Window owner) {
        new ProgressAdminWindow(owner).stage.show();
    }

    private ProgressAdminWindow(Window owner) {
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("CodeRun - Player Progress");

        // ---------- left: search + player cards
        TextField search = new TextField();
        search.setPromptText("Search player...");
        search.setStyle(fieldStyle(15) + "-fx-pref-height: 38;");
        search.textProperty().addListener((o, a, q) -> filter(q));

        players.setCellFactory(lv -> new PlayerCell());
        players.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent; -fx-padding: 2;");
        players.setFixedCellSize(64);
        players.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> {
            if (now == null || now.name().equals(current)) return;
            if (dirty && !confirm("You have unsaved changes for " + current + ". Discard them?")) {
                // go back to the previous player without reloading
                PlayerRow back = was;
                javafx.application.Platform.runLater(() -> players.getSelectionModel().select(back));
                return;
            }
            load(now.name());
        });
        Button refresh = button("Refresh list", WOOD, DARK_TEAL, 14);
        refresh.setOnAction(e -> loadPlayers());
        Label playersTitle = new Label("Players");
        playersTitle.setStyle("-fx-text-fill: " + YELLOW + "; -fx-font-size: 24px; -fx-font-weight: bold;");
        VBox left = new VBox(10, playersTitle, search, players, refresh);
        VBox.setVgrow(players, Priority.ALWAYS);
        left.setPadding(new Insets(16));
        left.setPrefWidth(290);
        left.setMinWidth(260);
        left.setStyle(panelStyle());

        // ---------- right: the editor
        title.setStyle("-fx-text-fill: " + YELLOW + "; -fx-font-size: 28px; -fx-font-weight: bold;");
        dirtyLabel.setStyle("-fx-text-fill: #ffb86b; -fx-font-size: 14px; -fx-font-weight: bold;");
        HBox header = new HBox(16, title, dirtyLabel);
        header.setAlignment(Pos.BASELINE_LEFT);

        VBox wallet = section("Wallet",
                numberRow("coins", "Coins", 9_999_999, 10),
                numberRow("orbs", "Orbs", 9_999_999, 10));
        VBox stats = section("Stats",
                numberRow("matches_played", "Matches played", 99_999, 0),
                numberRow("wins", "Wins", 99_999, 0),
                numberRow("total_score", "Total score", 9_999_999, 10),
                numberRow("questions_answered", "Questions answered", 999_999, 0),
                numberRow("questions_correct", "Questions correct", 999_999, 0));
        VBox upgrades = section("Upgrades  (level 0 - " + ProgressService.MAX_LEVEL + ")",
                numberRow("boat_speed_lvl", "Boat Speed", ProgressService.MAX_LEVEL, 0),
                numberRow("boat_armor_lvl", "Boat Armor", ProgressService.MAX_LEVEL, 0),
                numberRow("rod_bite_lvl", "Rod Bite", ProgressService.MAX_LEVEL, 0),
                numberRow("rod_control_lvl", "Rod Control", ProgressService.MAX_LEVEL, 0));

        // skins: equipped (drop-downs with real names) + owned (click to toggle)
        List<ProgressService.Skin> boats = new ArrayList<>(), rods = new ArrayList<>();
        for (ProgressService.Skin s : ProgressService.SKINS) ("boat".equals(s.kind()) ? boats : rods).add(s);
        setupSkinBox(boatSkin, boats);
        setupSkinBox(rodSkin, rods);
        FlowPane boatChips = new FlowPane(8, 8), rodChips = new FlowPane(8, 8);
        for (ProgressService.Skin s : ProgressService.SKINS) {
            if (s.price() == 0) continue;               // starter skins are always owned
            ToggleButton chip = new ToggleButton(s.name());
            chip.setCursor(Cursor.HAND);
            chip.selectedProperty().addListener((o, a, on) -> { styleChip(chip); markDirty(); });
            styleChip(chip);
            ownedChips.put(s.id(), chip);
            ("boat".equals(s.kind()) ? boatChips : rodChips).getChildren().add(chip);
        }
        VBox skins = section("Skins",
                labeled("Equipped boat", boatSkin),
                labeled("Equipped rod", rodSkin),
                small("Owned boat skins (click to give / take away)"), boatChips,
                small("Owned rod skins"), rodChips);

        VBox col1 = new VBox(14, wallet, upgrades);
        VBox col2 = new VBox(14, stats, skins);
        HBox columns = new HBox(16, col1, col2);
        HBox.setHgrow(col1, Priority.ALWAYS);
        HBox.setHgrow(col2, Priority.ALWAYS);
        col1.setMaxWidth(Double.MAX_VALUE);
        col2.setMaxWidth(Double.MAX_VALUE);

        Button save = button("Save changes", "#7dd87d", "#123b17", 16);
        save.setOnAction(e -> save());
        Button undo = button("Undo changes", WOOD, DARK_TEAL, 15);
        undo.setOnAction(e -> { if (current != null) load(current); });
        Button reset = button("Reset progress", "#f2a3a3", "#5a1414", 15);
        reset.setOnAction(e -> reset());
        Button close = button("Close", WOOD, DARK_TEAL, 15);
        close.setOnAction(e -> {
            if (!dirty || confirm("Close without saving the changes for " + current + "?")) stage.close();
        });
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox buttons = new HBox(12, save, undo, reset, gap, close);
        buttons.setAlignment(Pos.CENTER_LEFT);
        status.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");

        editor.getChildren().addAll(header, columns, buttons, status);
        editor.setPadding(new Insets(18));
        editor.setStyle(panelStyle());
        ScrollPane scroll = new ScrollPane(editor);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        HBox root = new HBox(16, left, scroll);
        HBox.setHgrow(scroll, Priority.ALWAYS);
        root.setPadding(new Insets(16));
        root.setStyle("-fx-background-color: #0b141a;");

        Scene scene = new Scene(root, 1220, 800);
        scene.getStylesheets().add(Launcher.class.getResource("launcher.css").toExternalForm());
        stage.setScene(scene);
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        if (owner != null) {
            stage.setX(owner.getX() + Math.max(0, (owner.getWidth() - 1220) / 2));
            stage.setY(owner.getY() + Math.max(0, (owner.getHeight() - 800) / 2));
        }

        setEditable(false);
        loadPlayers();
    }

    // ------------------------------------------------------------------ building blocks

    private static String panelStyle() {
        return "-fx-background-color: " + PANEL + "; -fx-background-radius: 12; -fx-border-color: " + TEAL +
               "; -fx-border-width: 2; -fx-border-radius: 12;";
    }

    private static String fieldStyle(int size) {
        return "-fx-background-color: " + WOOD + "; -fx-background-radius: 8; -fx-text-fill: " + INK +
               "; -fx-font-size: " + size + "px; -fx-font-weight: bold;";
    }

    private static Button button(String text, String bg, String fg, int size) {
        Button b = new Button(text);
        String base = "-fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; -fx-font-size: " + size +
                "px; -fx-font-weight: bold; -fx-background-radius: 18; -fx-padding: 8 18 8 18;";
        b.setStyle(base);
        b.setCursor(Cursor.HAND);
        b.setOnMouseEntered(e -> b.setStyle(base + "-fx-opacity: 0.85;"));
        b.setOnMouseExited(e -> b.setStyle(base));
        return b;
    }

    private static Label small(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + CREAM + "; -fx-font-size: 13px;");
        return l;
    }

    private static VBox section(String name, Node... rows) {
        Label t = new Label(name);
        t.setStyle("-fx-text-fill: " + YELLOW + "; -fx-font-size: 18px; -fx-font-weight: bold;");
        VBox box = new VBox(10);
        box.getChildren().add(t);
        box.getChildren().addAll(rows);
        box.setPadding(new Insets(14));
        box.setStyle("-fx-background-color: rgba(255,255,255,0.05); -fx-background-radius: 10;");
        return box;
    }

    private static HBox labeled(String text, Node control) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + CREAM + "; -fx-font-size: 15px;");
        l.setMinWidth(170);
        HBox row = new HBox(10, l, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** One editable number: [label]  [-10] [-] [ value ] [+] [+10]  - type in the box or click. */
    private HBox numberRow(String column, String text, int max, int bigStep) {
        TextField f = new TextField("0");
        f.setPrefWidth(110);
        f.setPrefHeight(36);
        f.setAlignment(Pos.CENTER);
        f.setStyle(fieldStyle(17));
        f.textProperty().addListener((o, a, now) -> {
            if (!now.matches("\\d*")) { f.setText(now.replaceAll("[^\\d]", "")); return; }
            if (!now.isEmpty() && now.length() < 10 && Long.parseLong(now) > max) { f.setText(String.valueOf(max)); return; }
            markDirty();
        });
        numbers.put(column, f);

        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + CREAM + "; -fx-font-size: 15px;");
        l.setMinWidth(170);
        HBox row = new HBox(6, l);
        row.setAlignment(Pos.CENTER_LEFT);
        if (bigStep > 0) row.getChildren().add(step(f, -bigStep, max, "-" + bigStep));
        row.getChildren().addAll(step(f, -1, max, "-"), f, step(f, 1, max, "+"));
        if (bigStep > 0) row.getChildren().add(step(f, bigStep, max, "+" + bigStep));
        return row;
    }

    private static Button step(TextField f, int delta, int max, String text) {
        Button b = new Button(text);
        String base = "-fx-background-color: " + (delta > 0 ? "#9ccfc9" : "#e7c9a0") + "; -fx-text-fill: " + INK +
                "; -fx-font-size: 15px; -fx-font-weight: bold; -fx-background-radius: 8; -fx-min-width: 40; -fx-pref-height: 36;";
        b.setStyle(base);
        b.setCursor(Cursor.HAND);
        b.setOnAction(e -> {
            int v = value(f) + delta;
            f.setText(String.valueOf(Math.max(0, Math.min(max, v))));
        });
        return b;
    }

    private static int value(TextField f) {
        try { return f.getText().isEmpty() ? 0 : Integer.parseInt(f.getText()); }
        catch (NumberFormatException e) { return 0; }
    }

    private void setupSkinBox(ComboBox<ProgressService.Skin> box, List<ProgressService.Skin> items) {
        box.setItems(FXCollections.observableArrayList(items));
        box.setConverter(new StringConverter<>() {
            @Override public String toString(ProgressService.Skin s) {
                return s == null ? "" : s.name() + (s.price() == 0 ? "  (free)" : "  (" + s.price() + " coins)");
            }
            @Override public ProgressService.Skin fromString(String s) { return null; }
        });
        box.setPrefWidth(260);
        box.setStyle(fieldStyle(15));
        box.setCursor(Cursor.HAND);
        box.valueProperty().addListener((o, a, now) -> markDirty());
    }

    private static void styleChip(ToggleButton chip) {
        boolean on = chip.isSelected();
        chip.setStyle("-fx-background-color: " + (on ? "#7dd87d" : "rgba(255,255,255,0.12)") + "; -fx-text-fill: " +
                (on ? "#123b17" : CREAM) + "; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-radius: 16;" +
                " -fx-padding: 6 14 6 14; -fx-border-color: " + (on ? "#7dd87d" : "rgba(255,255,255,0.3)") +
                "; -fx-border-radius: 16;");
        chip.setText(chip.getText().replace("✓ ", ""));
        if (on) chip.setText("✓ " + chip.getText());
    }

    /** Player card in the list: name in big letters, coins / orbs / score underneath. */
    private final class PlayerCell extends ListCell<PlayerRow> {
        @Override protected void updateItem(PlayerRow p, boolean empty) {
            super.updateItem(p, empty);
            setText(null);
            if (empty || p == null) {
                setGraphic(null);
                setStyle("-fx-background-color: transparent;");
                return;
            }
            Label name = new Label(p.name());
            Label info = new Label(p.coins() + " coins   " + p.orbs() + " orbs   " + p.score() + " pts");
            boolean sel = isSelected();
            name.setStyle("-fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: " + (sel ? "#0e2b2c" : INK) + ";");
            info.setStyle("-fx-font-size: 12px; -fx-text-fill: " + (sel ? "#14302f" : "#6b4a2e") + ";");
            VBox box = new VBox(2, name, info);
            box.setPadding(new Insets(8, 12, 8, 12));
            box.setMaxWidth(Double.MAX_VALUE);
            box.setStyle("-fx-background-color: " + (sel ? "#9ccfc9" : WOOD) + "; -fx-background-radius: 10;" +
                    (sel ? " -fx-border-color: " + DARK_TEAL + "; -fx-border-width: 2; -fx-border-radius: 10;" : ""));
            setGraphic(box);
            setCursor(Cursor.HAND);
            setStyle("-fx-background-color: transparent; -fx-padding: 3 4 3 4;");
        }

        @Override public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            updateItem(getItem(), isEmpty());
        }
    }

    // ------------------------------------------------------------------ data

    private void loadPlayers() {
        String keep = current;
        allPlayers.clear();
        String sql = "SELECT u.username, COALESCE(p.coins, 0), COALESCE(p.orbs, 0), COALESCE(p.total_score, 0) " +
                "FROM users u LEFT JOIN player_progress p ON p.username = u.username ORDER BY u.username";
        try {
            ProgressService.ensureTables();
            try (Connection conn = DatabaseManager.getConnection();
                 Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) allPlayers.add(new PlayerRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)));
            }
            players.setItems(FXCollections.observableArrayList(allPlayers));
            if (keep != null) allPlayers.stream().filter(p -> p.name().equals(keep)).findFirst()
                    .ifPresent(p -> players.getSelectionModel().select(p));
            if (current == null) say("Click a player on the left to edit their progress.", OK);
        } catch (SQLException e) {
            say("Could not load players: " + e.getMessage(), ERR);
        }
    }

    private void filter(String q) {
        String f = q == null ? "" : q.trim().toLowerCase();
        players.setItems(FXCollections.observableArrayList(
                allPlayers.stream().filter(p -> p.name().toLowerCase().contains(f)).toList()));
    }

    private void say(String text, String color) {
        status.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        status.setText(text);
    }

    private void markDirty() {
        if (loading || current == null) return;
        dirty = true;
        dirtyLabel.setText("● Unsaved changes");
    }

    private void setEditable(boolean on) {
        editor.getChildren().get(1).setDisable(!on);
    }

    private boolean confirm(String text) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.YES, ButtonType.NO);
        a.initOwner(stage);
        a.setHeaderText(null);
        a.setTitle("Player Progress");
        a.getDialogPane().getStylesheets().add(Launcher.class.getResource("launcher.css").toExternalForm());
        a.getDialogPane().getStyleClass().add("themed-dialog");
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    @SuppressWarnings("unchecked")
    private void load(String username) {
        loading = true;
        try {
            Map<String, Object> m = ProgressService.adminLoad(username);
            current = username;
            title.setText(username);
            for (var e : numbers.entrySet()) {
                Object v = m.get(e.getKey());
                int n = v instanceof Integer i ? i : 0;
                e.getValue().setText(Integer.toString(n));
            }
            boatSkin.setValue(ProgressService.skin((String) m.getOrDefault("boat_skin", ProgressService.DEFAULT_BOAT)));
            rodSkin.setValue(ProgressService.skin((String) m.getOrDefault("rod_skin", ProgressService.DEFAULT_ROD)));
            Set<String> owned = (Set<String>) m.getOrDefault("owned", Set.of());
            ownedChips.forEach((id, chip) -> chip.setSelected(owned.contains(id)));
            setEditable(true);
            dirty = false;
            dirtyLabel.setText("");
            say("Editing " + username + ". Change anything, then press Save changes.", OK);
        } catch (SQLException e) {
            say("Could not load " + username + ": " + e.getMessage(), ERR);
        } finally {
            loading = false;
        }
    }

    private void save() {
        if (current == null) { say("Pick a player first.", ERR); return; }
        Map<String, Integer> nums = new HashMap<>();
        numbers.forEach((col, f) -> nums.put(col, value(f)));
        if (nums.getOrDefault("questions_correct", 0) > nums.getOrDefault("questions_answered", 0)) {
            say("Questions correct cannot be more than questions answered.", ERR);
            return;
        }
        Set<String> owned = new TreeSet<>();
        ownedChips.forEach((id, chip) -> { if (chip.isSelected()) owned.add(id); });
        ProgressService.Skin b = boatSkin.getValue() != null ? boatSkin.getValue() : ProgressService.skin(ProgressService.DEFAULT_BOAT);
        ProgressService.Skin r = rodSkin.getValue() != null ? rodSkin.getValue() : ProgressService.skin(ProgressService.DEFAULT_ROD);
        // a player can only wear what they own
        if (b.price() > 0) owned.add(b.id());
        if (r.price() > 0) owned.add(r.id());
        try {
            ProgressService.adminSave(current, nums, b.id(), r.id(), owned);
            String who = current;
            load(who);
            loadPlayers();
            say("Saved " + who + ".", OK);
        } catch (SQLException e) {
            say("Could not save: " + e.getMessage(), ERR);
        }
    }

    private void reset() {
        if (current == null) { say("Pick a player first.", ERR); return; }
        if (!confirm("Reset all progress for \"" + current + "\"? Coins, orbs, skins, upgrades and stats go back to zero. The account stays.")) return;
        try {
            ProgressService.adminReset(current);
            String who = current;
            load(who);
            loadPlayers();
            say("Progress reset for " + who + ".", OK);
        } catch (SQLException e) {
            say("Could not reset: " + e.getMessage(), ERR);
        }
    }
}

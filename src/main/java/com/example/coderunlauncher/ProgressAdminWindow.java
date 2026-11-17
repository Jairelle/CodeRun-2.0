package com.example.coderunlauncher;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.*;
import java.util.*;

/**
 * Admin window: the player-progress tables, shown as they are in the database.
 * Pick a table with the tabs at the top (Progress, Upgrades, Items, Outfit, Match history, Answers). Each one
 * lists its real rows (filter by player name); click a row to load it into the form below, then Add, Update or
 * Delete like on the Players screen. Opened from the Admin dashboard's "Player Progress" button.
 * Table and column names come only from the fixed list below; every value goes through a prepared statement.
 */
public final class ProgressAdminWindow {

    private static final String ERR = "#ff6b6b", OK = "#7dffb0";
    private static final String WOOD = "#f0d49b", TEAL = "#2b8a8e", INK = "#3b2314", CREAM = "#f3e4c0",
            YELLOW = "#f6d96b", PANEL = "rgba(22,38,46,0.94)";

    // ---------------------------------------------------------------- the tables

    enum Kind { TEXT, INT, BOOL, AUTO, READONLY }

    record Col(String name, Kind kind, boolean key) {
        boolean editable() { return kind != Kind.AUTO && kind != Kind.READONLY; }
    }

    record Table(String label, String name, String about, List<Col> cols, String order) {}

    private static Col key(String n) { return new Col(n, Kind.TEXT, true); }
    private static Col text(String n) { return new Col(n, Kind.TEXT, false); }
    private static Col num(String n) { return new Col(n, Kind.INT, false); }

    private static final List<Table> TABLES = List.of(
        new Table("Progress", "player_progress", "One row per player: wallet, level, stats and the boat and rod they use.",
            List.of(key("username"), num("coins"), num("orbs"), num("level"), num("matches_played"), num("wins"),
                    num("total_score"), num("questions_answered"), num("questions_correct"), text("boat_skin"), text("rod_skin"),
                    new Col("updated_at", Kind.READONLY, false)), "total_score DESC"),
        new Table("Upgrades", "player_upgrades", "One row per player and upgrade (boat_speed, boat_armor, rod_bite, rod_control): level 0-5.",
            List.of(key("username"), key("upgrade_id"), num("level")), "username, upgrade_id"),
        new Table("Items", "player_items", "Everything a player owns, one row per item. kind = boat, rod, shirt, hair, haircolor, hat, celebrate or frame.",
            List.of(key("username"), key("item_id"), text("kind"), new Col("acquired_at", Kind.READONLY, false)), "username, kind, item_id"),
        new Table("Outfit", "player_outfit", "What each player wears, one row per slot (shirt, hair, haircolor, hat, celebrate, frame).",
            List.of(key("username"), key("slot"), text("item_id")), "username, slot"),
        new Table("Match history", "match_results", "One row per island a player finished.",
            List.of(new Col("id", Kind.AUTO, true), text("username"), num("island"), num("score"), num("place_pos"), num("players"),
                    num("coins_earned"), num("orbs_earned"), num("questions_answered"), num("questions_correct"),
                    new Col("played_at", Kind.READONLY, false)), "id DESC"),
        new Table("Answers", "question_answers", "Every question a player answered on an island, and whether it was right (is_correct 1 = right).",
            List.of(new Col("id", Kind.AUTO, true), text("username"), num("island"), text("question"), text("answer_given"),
                    new Col("is_correct", Kind.BOOL, false), new Col("answered_at", Kind.READONLY, false)), "id DESC")
    );

    private static final int LIMIT = 500;

    // ---------------------------------------------------------------- window

    private VBox page;
    private final Pane overlay = new Pane();
    private final ListView<Opt> list = new ListView<>();
    private final TableView<Map<String, String>> grid = new TableView<>();
    private final TextField filter = new TextField();
    private final Label about = new Label(), count = new Label(), status = new Label(), tableName = new Label();
    private final GridPane form = new GridPane();
    private final Map<String, Control> fields = new LinkedHashMap<>();
    private Table table = TABLES.get(0);
    private Map<String, String> selected;

    /** The page the Admin dashboard shows in place of the Players table. */
    public static VBox page() {
        return new ProgressAdminWindow().page;
    }

    private ProgressAdminWindow() {
        try { ProgressService.ensureTables(); } catch (SQLException e) { say("Could not reach the database: " + e.getMessage(), ERR); }

        Label heading = new Label("Player Progress");
        heading.setStyle("-fx-text-fill: " + YELLOW + "; -fx-font-size: 26px; -fx-font-weight: bold;");

        // tabs: one per table
        ToggleGroup tabs = new ToggleGroup();
        HBox tabRow = new HBox(8);
        for (Table t : TABLES) {
            ToggleButton b = new ToggleButton(t.label());
            b.setToggleGroup(tabs);
            b.setUserData(t);
            b.setCursor(Cursor.HAND);
            String off = "-fx-background-color: " + WOOD + "; -fx-text-fill: " + INK + "; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-radius: 12; -fx-padding: 7 16 7 16;";
            String on = "-fx-background-color: " + TEAL + "; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-radius: 12; -fx-padding: 7 16 7 16;";
            b.setStyle(off);
            b.selectedProperty().addListener((o, was, now) -> b.setStyle(now ? on : off));
            tabRow.getChildren().add(b);
        }
        tabs.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) { if (was != null) was.setSelected(true); return; }
            table = (Table) now.getUserData();
            showTable();
        });

        about.setWrapText(true);
        about.setStyle("-fx-text-fill: " + CREAM + "; -fx-font-size: 14px;");
        tableName.setStyle("-fx-text-fill: " + YELLOW + "; -fx-font-size: 13px; -fx-font-family: monospace;");
        filter.setPromptText("Filter by player name");
        filter.setStyle("-fx-background-color: " + WOOD + "; -fx-background-radius: 16; -fx-text-fill: " + INK + "; -fx-font-size: 14px; -fx-pref-height: 34;");
        filter.setPrefWidth(260);
        filter.textProperty().addListener((o, a, b) -> reload());
        Button refresh = button("Refresh", WOOD, INK);
        refresh.setOnAction(e -> reload());
        count.setStyle("-fx-text-fill: " + CREAM + "; -fx-font-size: 13px;");
        Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
        HBox tools = new HBox(10, filter, count, gap, tableName, refresh);
        tools.setAlignment(Pos.CENTER_LEFT);

        grid.getStyleClass().add("admin-table");
        grid.setPlaceholder(new Label("No rows"));
        grid.getSelectionModel().selectedItemProperty().addListener((o, a, row) -> { if (row != null) load(row); });
        VBox.setVgrow(grid, Priority.ALWAYS);

        // form under the table
        form.setHgap(10); form.setVgap(8);
        Button add = button("Add", WOOD, INK), update = button("Update", WOOD, INK), delete = button("Delete", "#c0564f", "white"),
               clear = button("Clear", WOOD, INK), reset = button("Reset player", "#c0564f", "white");
        add.setOnAction(e -> insert());
        update.setOnAction(e -> update());
        delete.setOnAction(e -> delete());
        clear.setOnAction(e -> clearForm());
        reset.setOnAction(e -> resetPlayer());
        reset.setTooltip(new Tooltip("Deletes ALL of one player's progress, items, outfit and upgrades (keeps the account)"));
        status.setWrapText(true);
        HBox buttons = new HBox(10, add, update, delete, clear, status);
        Region g2 = new Region(); HBox.setHgrow(g2, Priority.ALWAYS);
        buttons.getChildren().addAll(g2, reset);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox bottom = new VBox(10, form, buttons);
        bottom.setPadding(new Insets(12));
        bottom.setStyle("-fx-background-color: rgba(240,212,155,0.08); -fx-background-radius: 12;");

        VBox root = new VBox(10, tabRow, about, tools, grid, bottom);
        root.setPadding(new Insets(12, 14, 12, 14));
        root.getStyleClass().add("board");
        // the dropdown lists open on this layer, inside the page (popup windows land in the middle of the screen in fullscreen)
        overlay.setVisible(false);
        overlay.setPickOnBounds(true);
        overlay.setOnMouseClicked(e -> { if (e.getTarget() == overlay) closeList(); });
        StackPane stack = new StackPane(root, overlay);
        VBox.setVgrow(stack, Priority.ALWAYS);
        page = new VBox(stack);
        tabs.getToggles().get(0).setSelected(true);
    }

    // ---------------------------------------------------------------- showing a table

    private void showTable() {
        about.setText(table.about());
        tableName.setText("Table: " + table.name());
        grid.getColumns().clear();
        for (Col c : table.cols()) {
            TableColumn<Map<String, String>, String> tc = new TableColumn<>(c.name() + (c.key() ? "  🔑" : ""));
            tc.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().getOrDefault(c.name(), "")));
            tc.setPrefWidth(c.name().equals("question") ? 320 : c.kind() == Kind.TEXT ? 150 : 110);
            grid.getColumns().add(tc);
        }
        form.getChildren().clear();
        fields.clear();
        int i = 0;
        for (Col c : table.cols()) {
            if (!c.editable()) continue;
            Label l = new Label(c.name());
            l.setStyle("-fx-text-fill: " + CREAM + "; -fx-font-size: 13px; -fx-font-weight: bold;");
            List<Opt> opts = options(c.name());
            Control f;
            if (opts != null) {
                f = dropdown(opts);
            } else {
                TextField t = new TextField();
                t.setPromptText(c.kind() == Kind.INT ? "number" : c.kind() == Kind.BOOL ? "0 or 1" : c.name());
                t.setStyle("-fx-background-color: " + WOOD + "; -fx-background-radius: 10; -fx-text-fill: " + INK + "; -fx-font-size: 14px;");
                t.setPrefWidth(c.name().equals("question") ? 360 : 170);
                f = t;
            }
            int colPos = (i % 4) * 2, rowPos = i / 4;
            form.add(l, colPos, rowPos);
            form.add(f, colPos + 1, rowPos);
            fields.put(c.name(), f);
            i++;
        }
        linkDropdowns();
        clearForm();
        reload();
    }

    private void reload() {
        selected = null;
        String like = filter.getText() == null ? "" : filter.getText().trim();
        String sql = "SELECT * FROM " + table.name() + (like.isEmpty() ? "" : " WHERE username LIKE ?") +
                " ORDER BY " + table.order() + " LIMIT " + LIMIT;
        List<Map<String, String>> rows = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            if (!like.isEmpty()) ps.setString(1, "%" + like + "%");
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> row = new HashMap<>();
                    for (Col c : table.cols()) {
                        Object v = rs.getObject(c.name());
                        row.put(c.name(), v == null ? "" : v instanceof Boolean bo ? (bo ? "1" : "0") : String.valueOf(v));
                    }
                    rows.add(row);
                }
            }
            grid.getItems().setAll(rows);
            count.setText(rows.size() + (rows.size() >= LIMIT ? "+ rows (showing the first " + LIMIT + ")" : rows.size() == 1 ? " row" : " rows"));
        } catch (SQLException e) {
            grid.getItems().clear();
            count.setText("");
            say("Could not read " + table.name() + ": " + e.getMessage(), ERR);
        }
    }

    private void load(Map<String, String> row) {
        selected = row;
        for (String col : fields.keySet()) set(col, row.getOrDefault(col, ""));
        say("", OK);
    }

    private void clearForm() {
        selected = null;
        grid.getSelectionModel().clearSelection();
        for (String col : fields.keySet()) set(col, "");
    }

    // ---------------------------------------------------------------- writing

    /** Reads the form; null (with a message) if a value is wrong. */
    private Map<String, Object> values() {
        Map<String, Object> v = new LinkedHashMap<>();
        for (Col c : table.cols()) {
            if (!c.editable()) continue;
            String s = get(c.name()).trim();
            if (c.kind() == Kind.INT || c.kind() == Kind.BOOL) {
                try {
                    int n = Integer.parseInt(s);
                    if (c.kind() == Kind.BOOL && n != 0 && n != 1) throw new NumberFormatException();
                    if (n < 0) { say(c.name() + " can't be negative.", ERR); return null; }
                    v.put(c.name(), n);
                } catch (NumberFormatException e) {
                    say(c.name() + (c.kind() == Kind.BOOL ? " must be 0 or 1." : " must be a whole number."), ERR);
                    return null;
                }
            } else {
                if (s.isEmpty()) { say("Fill in " + c.name() + ".", ERR); return null; }
                v.put(c.name(), s);
            }
        }
        return v;
    }

    private void insert() {
        Map<String, Object> v = values();
        if (v == null) return;
        String cols = String.join(", ", v.keySet());
        String marks = String.join(", ", Collections.nCopies(v.size(), "?"));
        run("INSERT INTO " + table.name() + " (" + cols + ") VALUES (" + marks + ")", new ArrayList<>(v.values()), "Row added.");
    }

    private void update() {
        if (selected == null) { say("Click a row first.", ERR); return; }
        Map<String, Object> v = values();
        if (v == null) return;
        List<Object> args = new ArrayList<>(v.values());
        StringBuilder set = new StringBuilder();
        for (String c : v.keySet()) set.append(set.length() == 0 ? "" : ", ").append(c).append(" = ?");
        run("UPDATE " + table.name() + " SET " + set + where(args), args, "Row updated.");
    }

    private void delete() {
        if (selected == null) { say("Click a row first.", ERR); return; }
        if (!confirm("Delete this row from " + table.name() + "?")) return;
        List<Object> args = new ArrayList<>();
        run("DELETE FROM " + table.name() + where(args), args, "Row deleted.");
    }

    /** WHERE on the selected row's key columns (their values go into args). */
    private String where(List<Object> args) {
        StringBuilder w = new StringBuilder();
        for (Col c : table.cols()) {
            if (!c.key()) continue;
            w.append(w.length() == 0 ? " WHERE " : " AND ").append(c.name()).append(" = ?");
            String raw = selected.getOrDefault(c.name(), "");
            args.add(c.kind() == Kind.AUTO ? (Object) Integer.parseInt(raw) : raw);
        }
        return w.toString();
    }

    private void run(String sql, List<Object> args, String done) {
        try (Connection conn = DatabaseManager.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.size(); i++) ps.setObject(i + 1, args.get(i));
            int n = ps.executeUpdate();
            reload();
            clearForm();
            say(n > 0 ? done : "Nothing changed.", n > 0 ? OK : ERR);
        } catch (SQLException e) {
            String m = e.getErrorCode() == 1062 ? "That row already exists (same key)."
                     : e.getErrorCode() == 1452 ? "No player with that username."
                     : e.getMessage();
            say("Could not save: " + m, ERR);
        }
    }

    private void resetPlayer() {
        String user = selected != null ? selected.get("username") : fields.containsKey("username") ? get("username") : null;
        if (user == null || user.isBlank()) { say("Click one of the player's rows first.", ERR); return; }
        if (!confirm("Reset ALL progress of " + user + "?\nCoins, orbs, stats, items, outfit and upgrades are deleted. The account stays.")) return;
        try {
            ProgressService.adminReset(user);
            reload();
            clearForm();
            say(user + " starts fresh.", OK);
        } catch (SQLException e) {
            say("Could not reset: " + e.getMessage(), ERR);
        }
    }

    // ---------------------------------------------------------------- dropdowns

    /** One choice in a dropdown: the value saved in the database (id) and what the admin reads (label). */
    record Opt(String id, String label) {}

    private static final List<String> KINDS = List.of("boat", "rod", "shirt", "hair", "haircolor", "hat", "celebrate", "frame");
    private static final List<String> SLOTS = List.of("shirt", "hair", "haircolor", "hat", "celebrate", "frame");

    /** The choices for a column, or null when it is typed in (numbers, question text). */
    private List<Opt> options(String col) {
        List<Opt> o = new ArrayList<>();
        switch (col) {
            case "username" -> usernames().forEach(u -> o.add(new Opt(u, u)));
            case "upgrade_id" -> ProgressService.UPGRADES.forEach(u -> o.add(new Opt(u.id(), u.name() + "   (" + u.id() + ")")));
            case "boat_skin", "rod_skin" -> {
                String k = col.startsWith("boat") ? "boat" : "rod";
                for (ProgressService.Skin s : ProgressService.SKINS)
                    if (s.kind().equals(k)) o.add(new Opt(s.id(), s.name() + "   (" + s.id() + ")"));
            }
            case "item_id" -> {
                if (table.name().equals("player_items")) {
                    for (ProgressService.Skin s : ProgressService.SKINS)
                        o.add(new Opt(s.id(), s.kind() + ": " + s.name() + "   (" + s.id() + ")"));
                    for (ProgressService.Look l : ProgressService.LOOKS)
                        o.add(new Opt(l.id(), l.slot() + ": " + l.name() + "   (" + l.id() + ")"));
                } else {
                    o.addAll(looksFor(null));
                }
            }
            case "kind" -> KINDS.forEach(k -> o.add(new Opt(k, k)));
            case "slot" -> SLOTS.forEach(k -> o.add(new Opt(k, k)));
            case "level" -> {
                if (!table.name().equals("player_upgrades")) return null;   // player level is worked out from the score
                for (int i = 0; i <= ProgressService.MAX_LEVEL; i++) o.add(new Opt(String.valueOf(i), "Level " + i));
            }
            case "island" -> { for (int i = 1; i <= 3; i++) o.add(new Opt(String.valueOf(i), "Island " + i)); }
            case "is_correct" -> { o.add(new Opt("1", "1 - Right")); o.add(new Opt("0", "0 - Wrong")); }
            default -> { return null; }
        }
        return o;
    }

    /** Looks for one outfit slot (all of them when slot is null). */
    private static List<Opt> looksFor(String slot) {
        List<Opt> o = new ArrayList<>();
        for (ProgressService.Look l : ProgressService.LOOKS)
            if (slot == null || l.slot().equals(slot))
                o.add(new Opt(l.id(), (slot == null ? l.slot() + ": " : "") + l.name() + "   (" + l.id() + ")"));
        return o;
    }

    private List<String> usernames() {
        List<String> names = new ArrayList<>();
        try (Connection conn = DatabaseManager.getConnection(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT username FROM users ORDER BY username")) {
            while (rs.next()) names.add(rs.getString(1));
        } catch (SQLException e) {
            say("Could not load player names: " + e.getMessage(), ERR);
        }
        return names;
    }

    /**
     * A dropdown box: shows the chosen label; clicking opens the list right under it (or above it when there is
     * no room below), drawn inside the page.
     */
    private final class Pick extends Button {
        final List<Opt> items = new ArrayList<>();
        final javafx.beans.property.ObjectProperty<Opt> value = new javafx.beans.property.SimpleObjectProperty<>();

        Pick(List<Opt> opts) {
            items.addAll(opts);
            setPrefWidth(230);
            setMaxWidth(230);
            setAlignment(Pos.CENTER_LEFT);
            setCursor(Cursor.HAND);
            setTextOverrun(OverrunStyle.ELLIPSIS);
            setStyle("-fx-background-color: " + WOOD + "; -fx-background-radius: 10; -fx-text-fill: " + INK +
                     "; -fx-font-size: 14px; -fx-font-weight: bold; -fx-padding: 5 10 5 10;");
            Label arrow = new Label("▾");
            arrow.setStyle("-fx-text-fill: " + INK + "; -fx-font-size: 14px;");
            setGraphic(arrow);
            setContentDisplay(ContentDisplay.RIGHT);
            setGraphicTextGap(8);
            value.addListener((o, a, now) -> setText(now == null ? "Choose..." : now.label()));
            setText("Choose...");
            setOnAction(e -> openList(this));
        }
    }

    private void openList(Pick pick) {
        list.getItems().setAll(pick.items);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(Opt o, boolean empty) {
                super.updateItem(o, empty);
                setText(empty || o == null ? null : o.label());
                setStyle("-fx-font-size: 14px; -fx-padding: 6 10 6 10;");
            }
        });
        list.setFixedCellSize(32);
        list.setStyle("-fx-background-color: " + WOOD + "; -fx-background-radius: 10; -fx-border-color: " + TEAL +
                      "; -fx-border-width: 2; -fx-border-radius: 10;");
        list.getSelectionModel().select(pick.value.get());
        list.setOnMouseClicked(e -> {
            Opt o = list.getSelectionModel().getSelectedItem();
            if (o != null) pick.value.set(o);
            closeList();
        });
        list.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ENTER -> { Opt o = list.getSelectionModel().getSelectedItem(); if (o != null) pick.value.set(o); closeList(); }
                case ESCAPE -> closeList();
                default -> { }
            }
        });

        // place it under the box, inside the page
        javafx.geometry.Bounds b = overlay.sceneToLocal(pick.localToScene(pick.getBoundsInLocal()));
        double h = Math.min(pick.items.size(), 10) * 32 + 6;
        double w = Math.max(b.getWidth(), 300);
        double y = b.getMaxY() + 4;
        if (y + h > overlay.getHeight() && b.getMinY() - h - 4 > 0) y = b.getMinY() - h - 4;   // no room below: open upwards
        double x = Math.min(b.getMinX(), Math.max(0, overlay.getWidth() - w));
        list.setPrefSize(w, h);
        list.resize(w, h);
        list.relocate(x, y);
        overlay.getChildren().setAll(list);
        overlay.setVisible(true);
        list.requestFocus();
        if (pick.value.get() != null) list.scrollTo(pick.value.get());
    }

    private void closeList() {
        overlay.setVisible(false);
        overlay.getChildren().clear();
    }

    private Pick dropdown(List<Opt> opts) { return new Pick(opts); }

    private static Pick asBox(Control c) { return c instanceof Pick p ? p : null; }

    /** Picking an item fills in its kind; picking an outfit slot shows only the looks for that slot. */
    private void linkDropdowns() {
        Pick item = asBox(fields.get("item_id")), kind = asBox(fields.get("kind")), slot = asBox(fields.get("slot"));
        if (item != null && kind != null) {
            item.value.addListener((o, a, now) -> {
                if (now == null) return;
                ProgressService.Skin s = ProgressService.skin(now.id());
                ProgressService.Look l = ProgressService.look(now.id());
                String k = s != null ? s.kind() : l != null ? l.slot() : null;
                if (k != null) set("kind", k);
            });
        }
        if (item != null && slot != null) {
            slot.value.addListener((o, a, now) -> {
                String keep = get("item_id");
                item.items.clear();
                item.items.addAll(looksFor(now == null ? null : now.id()));
                item.value.set(item.items.stream().filter(x -> x.id().equals(keep)).findFirst().orElse(null));
            });
        }
    }

    /** The value in a form field (a dropdown gives its id). */
    private String get(String col) {
        Control c = fields.get(col);
        Pick box = asBox(c);
        if (box != null) return box.value.get() == null ? "" : box.value.get().id();
        String t = ((TextField) c).getText();
        return t == null ? "" : t;
    }

    private void set(String col, String value) {
        Control c = fields.get(col);
        Pick box = asBox(c);
        if (box == null) { ((TextField) c).setText(value); return; }
        if (value == null || value.isEmpty()) { box.value.set(null); return; }
        Opt pick = box.items.stream().filter(o -> o.id().equals(value)).findFirst().orElse(null);
        if (pick == null) {                       // a value from the database that is not in the list any more
            pick = new Opt(value, value + "   (not in list)");
            box.items.add(pick);
        }
        box.value.set(pick);
    }

    // ---------------------------------------------------------------- helpers

    private boolean confirm(String text) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.YES, ButtonType.NO);
        if (page.getScene() != null) a.initOwner(page.getScene().getWindow());
        a.setHeaderText(null);
        a.setTitle("Player Progress");
        a.getDialogPane().getStylesheets().add(Launcher.class.getResource("launcher.css").toExternalForm());
        a.getDialogPane().getStyleClass().add("themed-dialog");
        return a.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    private void say(String text, String color) {
        status.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        status.setText(text);
    }

    private static Button button(String text, String bg, String fg) {
        Button b = new Button(text);
        String base = "-fx-background-color: " + bg + "; -fx-text-fill: " + fg + "; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-radius: 16; -fx-padding: 7 18 7 18;";
        b.setStyle(base);
        b.setCursor(Cursor.HAND);
        b.setOnMouseEntered(e -> b.setStyle(base + "-fx-opacity: 0.85;"));
        b.setOnMouseExited(e -> b.setStyle(base));
        return b;
    }
}

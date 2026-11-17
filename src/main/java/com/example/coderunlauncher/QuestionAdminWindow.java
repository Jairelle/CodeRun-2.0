package com.example.coderunlauncher;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Admin window: write the questions each island asks. Pick an island at the top (each has its own topic),
 * pick a question on the left to edit it, or press "New question". Every question has four answers; tick the
 * correct one. Opened from the Admin dashboard's "Island Questions" button.
 * Changes are saved to the database straight away and are used the next time the game is started.
 */
public final class QuestionAdminWindow {

    private static final String ERR = "#ff6b6b", OK = "#7dffb0";
    private static final String WOOD = "#f0d49b", TEAL = "#2b8a8e", DARK_TEAL = "#1f6568", INK = "#3b2314",
            CREAM = "#f3e4c0", YELLOW = "#f6d96b", PANEL = "rgba(22,38,46,0.92)";

    private VBox page;
    private final ToggleGroup islandGroup = new ToggleGroup();
    private final TableView<IslandQuestions.Question> list = new TableView<>();
    private final Label tableName = new Label();
    private final Label countLabel = new Label();
    private final Label formTitle = new Label("New question");
    private final TextArea questionField = new TextArea();
    private final TextField[] answers = new TextField[4];
    private final RadioButton[] correct = new RadioButton[4];
    private final ToggleGroup correctGroup = new ToggleGroup();
    private final Button deleteButton = button("Delete", "#c0564f", "white", 15);
    private final Label status = new Label();
    private int topic;                               // index into IslandQuestions.TOPICS
    private IslandQuestions.Question editing;        // null = a new question
    private boolean loading;

    /** The page the Admin dashboard shows in place of the Players table. */
    public static VBox page() {
        return new QuestionAdminWindow().page;
    }

    private QuestionAdminWindow() {

        // ---------- top: one button per island
        HBox islands = new HBox(10);
        islands.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < IslandQuestions.TOPICS.length; i++) {
            ToggleButton b = new ToggleButton(IslandQuestions.ISLANDS[i] + "\n" + IslandQuestions.TOPIC_LONG[i]);
            b.setToggleGroup(islandGroup);
            b.setUserData(i);
            b.setCursor(Cursor.HAND);
            b.setMinHeight(58);
            String off = "-fx-background-color: " + WOOD + "; -fx-text-fill: " + INK + "; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-radius: 12; -fx-padding: 6 16 6 16;";
            String on = "-fx-background-color: " + TEAL + "; -fx-text-fill: white; -fx-font-size: 14px; -fx-font-weight: bold; -fx-background-radius: 12; -fx-padding: 6 16 6 16;";
            b.setStyle(off);
            b.selectedProperty().addListener((o, was, now) -> b.setStyle(now ? on : off));
            islands.getChildren().add(b);
        }
        islandGroup.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) { if (was != null) was.setSelected(true); return; }   // one island is always selected
            topic = (Integer) now.getUserData();
            reload(-1);
            newQuestion();
        });
        Label heading = new Label("Island Questions");
        heading.setStyle("-fx-text-fill: " + YELLOW + "; -fx-font-size: 26px; -fx-font-weight: bold;");
        Label hint = new Label("Each island asks questions about its own topic. Keep them short and basic - players answer while running, fishing or sailing.");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: " + CREAM + "; -fx-font-size: 14px;");
        VBox top = new VBox(8, hint, islands);

        // ---------- left: the island's questions
        // the island's own table, shown like the Players table: one row per question
        list.getStyleClass().add("admin-table");
        list.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        list.setPlaceholder(new Label("No questions yet - press New question"));
        list.getColumns().add(col("ID", 46, q -> String.valueOf(q.id())));
        list.getColumns().add(col("Question", 260, IslandQuestions.Question::question));
        for (int k = 0; k < 4; k++) {
            final int kk = k;
            list.getColumns().add(col("option_" + (char) ('a' + k), 110, q -> q.options()[kk]));
        }
        list.getColumns().add(col("Correct", 64, q -> String.valueOf((char) ('A' + Math.max(0, Math.min(3, q.correct()))))));
        list.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> { if (!loading && now != null) edit(now); });
        countLabel.setStyle("-fx-text-fill: " + CREAM + "; -fx-font-size: 14px; -fx-font-weight: bold;");
        countLabel.setWrapText(true);
        Button newButton = button("+  New question", YELLOW, INK, 15);
        newButton.setOnAction(e -> newQuestion());
        newButton.setMaxWidth(Double.MAX_VALUE);
        tableName.setStyle("-fx-text-fill: " + YELLOW + "; -fx-font-size: 13px; -fx-font-family: monospace;");
        VBox left = new VBox(8, countLabel, tableName, list, newButton);
        VBox.setVgrow(list, Priority.ALWAYS);
        left.setPrefWidth(720);
        left.setMinWidth(480);
        HBox.setHgrow(left, Priority.ALWAYS);

        // ---------- right: the form
        formTitle.setStyle("-fx-text-fill: " + YELLOW + "; -fx-font-size: 22px; -fx-font-weight: bold;");
        questionField.setPromptText("Type the question here");
        questionField.setWrapText(true);
        questionField.setPrefRowCount(3);
        questionField.setStyle("-fx-control-inner-background: " + WOOD + "; -fx-text-fill: " + INK + "; -fx-font-size: 16px; -fx-font-weight: bold; -fx-background-radius: 8;");
        VBox form = new VBox(10, formTitle, label("Question"), questionField, label("Answers  (tick the correct one)"));
        for (int i = 0; i < 4; i++) {
            char letter = (char) ('A' + i);
            correct[i] = new RadioButton(String.valueOf(letter));
            correct[i].setToggleGroup(correctGroup);
            correct[i].setCursor(Cursor.HAND);
            correct[i].setMinWidth(54);
            correct[i].setStyle("-fx-text-fill: " + CREAM + "; -fx-font-size: 17px; -fx-font-weight: bold;");
            answers[i] = new TextField();
            answers[i].setPromptText("Answer " + letter);
            answers[i].setStyle("-fx-background-color: " + WOOD + "; -fx-background-radius: 8; -fx-text-fill: " + INK + "; -fx-font-size: 15px; -fx-font-weight: bold; -fx-pref-height: 38;");
            HBox row = new HBox(8, correct[i], answers[i]);
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(answers[i], Priority.ALWAYS);
            form.getChildren().add(row);
        }
        Button saveButton = button("Save question", TEAL, "white", 16);
        saveButton.setOnAction(e -> save());
        deleteButton.setOnAction(e -> delete());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox buttons = new HBox(10, saveButton, gap, deleteButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        status.setWrapText(true);
        Label note = new Label("Saved questions are used the next time the game starts. If an island has fewer than " +
                IslandQuestions.MIN_PER_TOPIC + " questions, the game adds its built-in starter questions for that topic.");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: " + CREAM + "; -fx-font-size: 13px; -fx-opacity: 0.85;");
        form.getChildren().addAll(buttons, status, note);
        form.setPadding(new Insets(16));
        form.setStyle("-fx-background-color: rgba(240,212,155,0.08); -fx-background-radius: 12;");
        form.setPrefWidth(470);
        form.setMinWidth(420);

        HBox body = new HBox(16, left, form);
        VBox.setVgrow(body, Priority.ALWAYS);
        VBox root = new VBox(14, top, body);
        root.setPadding(new Insets(12, 14, 12, 14));
        root.getStyleClass().add("board");

        page = root;

        // first open: make sure the starter questions are there, then show island 1
        try {
            int added = IslandQuestions.seedStarterIfEmpty();
            if (added > 0) say("Added " + added + " starter questions. Edit them or add your own.", OK);
        } catch (SQLException e) {
            say("Could not reach the database: " + e.getMessage(), ERR);
        }
        islandGroup.getToggles().get(0).setSelected(true);
    }

    // ------------------------------------------------------------------ list

    /** Reloads the island's questions and selects the one with this id (-1 = none). */
    private void reload(int selectId) {
        loading = true;
        try {
            List<IslandQuestions.Question> items = IslandQuestions.list(IslandQuestions.TOPICS[topic]);
            list.getItems().setAll(items);
            int n = items.size();
            tableName.setText("Table: " + IslandQuestions.table(topic));
            countLabel.setText(IslandQuestions.ISLANDS[topic] + ":  " + n + (n == 1 ? " question" : " questions") +
                    (n < IslandQuestions.MIN_PER_TOPIC ? "   (add at least " + (IslandQuestions.MIN_PER_TOPIC - n) + " more)" : ""));
            list.getSelectionModel().clearSelection();
            for (IslandQuestions.Question q : items)
                if (q.id() == selectId) { list.getSelectionModel().select(q); list.scrollTo(q); }
        } catch (SQLException e) {
            list.getItems().setAll(new ArrayList<>());
            countLabel.setText("");
            say("Could not load the questions: " + e.getMessage(), ERR);
        } finally {
            loading = false;
        }
    }

    // ------------------------------------------------------------------ form

    private void newQuestion() {
        editing = null;
        loading = true;
        list.getSelectionModel().clearSelection();
        loading = false;
        formTitle.setText("New question  -  " + IslandQuestions.TOPIC_LONG[topic]);
        questionField.clear();
        for (int i = 0; i < 4; i++) { answers[i].clear(); correct[i].setSelected(false); }
        deleteButton.setDisable(true);
        questionField.requestFocus();
    }

    private void edit(IslandQuestions.Question q) {
        editing = q;
        formTitle.setText("Edit question  -  " + IslandQuestions.TOPIC_LONG[topic]);
        questionField.setText(q.question());
        for (int i = 0; i < 4; i++) { answers[i].setText(q.options()[i]); correct[i].setSelected(i == q.correct()); }
        deleteButton.setDisable(false);
        say("", OK);
    }

    private void save() {
        int right = -1;
        for (int i = 0; i < 4; i++) if (correct[i].isSelected()) right = i;
        String[] opts = new String[4];
        for (int i = 0; i < 4; i++) opts[i] = answers[i].getText() == null ? "" : answers[i].getText().trim();
        String text = questionField.getText() == null ? "" : questionField.getText().trim().replaceAll("\\s+", " ");
        IslandQuestions.Question q = new IslandQuestions.Question(editing == null ? 0 : editing.id(),
                IslandQuestions.TOPICS[topic], text, opts, right);
        String problem = IslandQuestions.problem(q);
        if (problem != null) { say(problem, ERR); return; }
        try {
            if (editing == null) {
                IslandQuestions.add(q);
                reload(-1);
                // select the new one (the last in the list) so it can be checked
                if (!list.getItems().isEmpty()) {
                    IslandQuestions.Question last = list.getItems().get(list.getItems().size() - 1);
                    loading = true; list.getSelectionModel().select(last); list.scrollTo(last); loading = false;
                    edit(last);
                }
                say("Question added.", OK);
            } else {
                IslandQuestions.update(q);
                int id = q.id();
                reload(id);
                for (IslandQuestions.Question x : list.getItems()) if (x.id() == id) edit(x);
                say("Changes saved.", OK);
            }
        } catch (SQLException e) {
            say(IslandQuestions.isDuplicate(e) ? "This island already has that question." : "Could not save: " + e.getMessage(), ERR);
        }
    }

    private void delete() {
        if (editing == null) return;
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Delete this question?\n\n" + editing.question(), ButtonType.YES, ButtonType.NO);
        if (page.getScene() != null) a.initOwner(page.getScene().getWindow());
        a.setHeaderText(null);
        a.setTitle("Island Questions");
        a.getDialogPane().getStylesheets().add(Launcher.class.getResource("launcher.css").toExternalForm());
        a.getDialogPane().getStyleClass().add("themed-dialog");
        if (a.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        try {
            IslandQuestions.delete(editing.topic(), editing.id());
            reload(-1);
            newQuestion();
            say("Question deleted.", OK);
        } catch (SQLException e) {
            say("Could not delete: " + e.getMessage(), ERR);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void say(String text, String color) {
        status.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: " + color + ";");
        status.setText(text);
    }

    private static TableColumn<IslandQuestions.Question, String> col(String name, double width,
                                                                     java.util.function.Function<IslandQuestions.Question, String> value) {
        TableColumn<IslandQuestions.Question, String> c = new TableColumn<>(name);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new javafx.beans.property.SimpleStringProperty(value.apply(cd.getValue())));
        return c;
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-text-fill: " + CREAM + "; -fx-font-size: 15px; -fx-font-weight: bold;");
        return l;
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
}

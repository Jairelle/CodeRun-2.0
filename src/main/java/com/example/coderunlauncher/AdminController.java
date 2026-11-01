package com.example.coderunlauncher;

import at.favre.lib.crypto.bcrypt.BCrypt;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.io.IOException;
import java.sql.*;

public class AdminController {

    @FXML private TableView<User> usersTable;
    @FXML private TextField txtUsername;
    @FXML private TextField txtEmail;
    @FXML private PasswordField txtPassword;
    @FXML private ComboBox<String> comboRole;
    @FXML private Label statusLabel;
    @FXML private Label countLabel;
    @FXML private TextField searchField;
    @FXML private TableColumn<User, Integer> idColumn;
    @FXML private TableColumn<User, String> passwordColumn;
    @FXML private TableColumn<User, String> roleColumn;
    @FXML private ImageView adminBg;

    private static final String ERR = "#ff6b6b";
    private static final String OK = "#7dffb0";

    private User selectedUser;
    private final ObservableList<User> allUsers = FXCollections.observableArrayList();
    private final FilteredList<User> shownUsers = new FilteredList<>(allUsers, u -> true);

    @FXML
    public void initialize() {
        comboRole.setItems(FXCollections.observableArrayList("PLAYER", "ADMIN"));

        // background picture always fills the window
        if (adminBg != null && adminBg.getParent() instanceof StackPane root) {
            adminBg.fitWidthProperty().bind(root.widthProperty());
            adminBg.fitHeightProperty().bind(root.heightProperty());
        }

        // The database id can have gaps (MySQL skips numbers when an insert fails), so show 1, 2, 3...
        idColumn.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Integer item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : String.valueOf(getIndex() + 1));
            }
        });

        // never show password hashes on screen
        passwordColumn.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : "\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF\u25CF");
            }
        });
        roleColumn.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                setStyle(empty ? "" : ("ADMIN".equals(item) ? "-fx-font-weight: bold; -fx-text-fill: #a3382b;" : ""));
            }
        });

        usersTable.setItems(shownUsers);
        searchField.textProperty().addListener((obs, was, now) -> {
            String q = now == null ? "" : now.trim().toLowerCase();
            shownUsers.setPredicate(u -> q.isEmpty()
                    || u.getUsername().toLowerCase().contains(q)
                    || (u.getEmail() != null && u.getEmail().toLowerCase().contains(q)));
            updateCount();
        });

        loadUsers();
    }

    private void updateCount() {
        countLabel.setText(shownUsers.size() == allUsers.size()
                ? allUsers.size() + (allUsers.size() == 1 ? " player" : " players")
                : shownUsers.size() + " of " + allUsers.size() + " players");
    }

    private void status(String message, String color) {
        statusLabel.setStyle("-fx-text-fill: " + color + ";");
        statusLabel.setText(message);
    }

    /** Back to the launcher's login screen. */
    @FXML
    private void handleLogout() {
        try {
            Launcher.showLauncher((Stage) usersTable.getScene().getWindow());
        } catch (IOException e) {
            status("Could not return to the launcher: " + e.getMessage(), ERR);
        }
    }

    /** Opens the Player Progress editor (coins, orbs, skins, upgrades, stats). */
    @FXML
    private void openProgress() {
        ProgressAdminWindow.open(usersTable.getScene().getWindow());
    }

    @FXML
    private void handleQuit() {
        Platform.exit();
        System.exit(0);
    }

    @FXML
    private void loadUsers() {
        ObservableList<User> userList = FXCollections.observableArrayList();
        String sql = "SELECT id, username, email, password, role FROM users";

        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                userList.add(new User(
                        rs.getInt("id"),
                        rs.getString("username"),
                        rs.getString("email"),
                        rs.getString("password"),
                        rs.getString("role")));
            }
            allUsers.setAll(userList);
            updateCount();
            status("Database loaded.", OK);
        } catch (SQLException e) {
            status("Error loading users: " + e.getMessage(), ERR);
        }
    }

    @FXML
    private void handleRowSelect() {
        selectedUser = usersTable.getSelectionModel().getSelectedItem();
        if (selectedUser != null) {
            txtUsername.setText(selectedUser.getUsername());
            txtEmail.setText(selectedUser.getEmail() == null ? "" : selectedUser.getEmail());
            comboRole.setValue(selectedUser.getRole());
            txtPassword.clear();
        }
    }

    @FXML
    private void handleAddUser() {
        String user = txtUsername.getText().trim();
        String email = txtEmail.getText().trim();
        String pass = txtPassword.getText().trim();
        String role = comboRole.getValue();

        if (user.isEmpty() || pass.isEmpty() || role == null) {
            status("All fields (including password) are required to add a user.", ERR);
            return;
        }

        String hashedPassword = BCrypt.withDefaults().hashToString(12, pass.toCharArray());

        String sql = "INSERT INTO users (username, email, password, role) VALUES (?, ?, ?, ?)";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, user);
            pstmt.setString(2, email.isEmpty() ? null : email);   // empty email stored as NULL (column is UNIQUE)
            pstmt.setString(3, hashedPassword);
            pstmt.setString(4, role);

            pstmt.executeUpdate();
            loadUsers();
            clearFields();

        } catch (SQLException e) {
            status("Error adding user: " + e.getMessage(), ERR);
        }
    }

    @FXML
    private void handleUpdateUser() {
        if (selectedUser == null) {
            status("Please select a user from the table first.", ERR);
            return;
        }

        String user = txtUsername.getText().trim();
        String email = txtEmail.getText().trim();
        String pass = txtPassword.getText().trim();
        String role = comboRole.getValue();

        String sql = pass.isEmpty() ?
                "UPDATE users SET username = ?, email = ?, role = ? WHERE id = ?" :
                "UPDATE users SET username = ?, email = ?, password = ?, role = ? WHERE id = ?";

        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, user);
            pstmt.setString(2, email.isEmpty() ? null : email);
            if (pass.isEmpty()) {
                pstmt.setString(3, role);
                pstmt.setInt(4, selectedUser.getId());
            } else {
                String hashedPassword = BCrypt.withDefaults().hashToString(12, pass.toCharArray());
                pstmt.setString(3, hashedPassword);
                pstmt.setString(4, role);
                pstmt.setInt(5, selectedUser.getId());
            }
            pstmt.executeUpdate();
            loadUsers();
            clearFields();
        } catch (SQLException e) {
            status("Error updating user: " + e.getMessage(), ERR);
        }
    }

    @FXML
    private void handleDeleteUser() {
        if (selectedUser == null) {
            status("Please select a user to delete.", ERR);
            return;
        }

        if (selectedUser.getUsername().equals("jairelle")) {
            status("Security: Cannot delete the master admin account.", ERR);
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete player \"" + selectedUser.getUsername() + "\"? This cannot be undone.",
                ButtonType.YES, ButtonType.CANCEL);
        confirm.setHeaderText(null);
        confirm.setTitle("Delete player");
        confirm.getDialogPane().getStylesheets().add(getClass().getResource("launcher.css").toExternalForm());
        confirm.getDialogPane().getStyleClass().add("themed-dialog");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.YES) return;

        String sql = "DELETE FROM users WHERE id = ?";
        try (Connection conn = DatabaseManager.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, selectedUser.getId());
            pstmt.executeUpdate();
            loadUsers();
            clearFields();
        } catch (SQLException e) {
            status("Error deleting user: " + e.getMessage(), ERR);
        }
    }

    private void clearFields() {
        status("Done.", OK);
        txtUsername.clear();
        txtEmail.clear();
        txtPassword.clear();
        comboRole.setValue(null);
        selectedUser = null;
    }
}
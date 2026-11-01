package com.example.coderunlauncher;

import at.favre.lib.crypto.bcrypt.BCrypt;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import javafx.animation.FadeTransition;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class LauncherController {

    // --- Background & View Containers ---
    @FXML private ImageView bgImageView;
    @FXML private VBox mainMenuPane;
    @FXML private VBox loginPane;
    @FXML private VBox signUpPane;

    // --- Login Form Controls ---
    //@FXML private Label titleLabel;
    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Button actionButton;
    @FXML private Button toggleButton;
    @FXML private Button backButton;
    @FXML private Label statusLabel;
    @FXML private ProgressIndicator loginSpinner;
    @FXML private CheckBox rememberCheck;

    // --- Forgot password pane ---
    @FXML private VBox forgotPane;
    @FXML private Button forgotButton;
    @FXML private TextField resetEmailField;
    @FXML private TextField resetCodeField;
    @FXML private TextField rc1, rc2, rc3, rc4, rc5, rc6;
    @FXML private PasswordField resetPasswordField;
    @FXML private Button resetSendButton;
    @FXML private Button resetButton;
    @FXML private Label resetStatusLabel;

    // --- Settings pane ---
    @FXML private VBox settingsPane;
    @FXML private CheckBox fullscreenCheck;
    @FXML private CheckBox closeOnLaunchCheck;
    @FXML private ComboBox<String> resolutionCombo;
    @FXML private Label settingsStatusLabel;

    // --- Main Menu Custom Image Buttons ---
    @FXML private Button loginButton;
    @FXML private Button signUpButton;
    @FXML private Button settingsButton;
    @FXML private Button exitButton;
    @FXML private Button githubButton;

    // --- Sign Up Pane Fields ---
    @FXML private TextField signUpUsernameField;
    @FXML private TextField emailField;
    @FXML private PasswordField signUpPasswordField;
    @FXML private ImageView eyeIcon;
    @FXML private Button sendCodeButton;
    @FXML private Button registerButton;
    @FXML private Button signUpToggleButton;
    @FXML private Label signUpStatusLabel;
    @FXML private HBox otpBox;
    @FXML private TextField otp1;
    @FXML private TextField otp2;
    @FXML private TextField otp3;
    @FXML private TextField otp4;
    @FXML private TextField otp5;
    @FXML private TextField otp6;

    private static final String ERR = "#ff6b6b";
    private static final String OK = "#7dffb0";
    private static final String INFO = "white";

    private final LauncherSettings settings = LauncherSettings.load();
    private boolean busy = false;          // a login / launch is in progress
    private String resetOtp;               // code e-mailed for a password reset
    private String resetEmail;             // address that code was sent to
    private long resetExpiresAt;
    private int resetAttempts;
    private long lastResetSend;
    private ImageView bgOverlay;           // second layer used to cross-fade backgrounds
    private FadeTransition bgFade;

    private String generatedOtp; // Stores the correct code temporarily
    private boolean isLoginMode = true;
    private boolean signUpPasswordVisible = false;
    private TextField signUpPasswordTextField; // For toggling password visibility
    private String otpEmail;        // Email the current code was sent to
    private long otpExpiresAt;      // Epoch millis after which the code is no longer valid
    private boolean fillingOtp = false; // true while a pasted code is being spread over the boxes

    // Background Image Cache
    private Image defaultBg;
    /** True after "close on launch" hid the window; the launcher quits when the game closes. */
    private volatile boolean hiddenForGame;
    private Image loginBg;
    private Image signUpBg;

    @FXML
    public void initialize() {
        loadBackgroundImages();
        // Database set-up and the trivia top-up use the network: never block the window on them
        Thread boot = new Thread(() -> {
            createTableIfNotExists();
            try {
                ProgressService.ensureTables();      // coins, orbs, skins, upgrades and stats
            } catch (Exception e) {
                System.err.println("Progress tables: " + e.getMessage());
            }
            startTriviaTopUp();
        }, "launcher-boot");
        boot.setDaemon(true);
        boot.start();
        setupButtonHoverEffects();
        setupOtpAutoAdvance();
        setupPasswordToggle();
        setupSettingsAndPrefs();
        setupEnterKeys();
        playIntro();
        // Start with status and back hidden
        if (statusLabel != null) statusLabel.setVisible(false);
        if (backButton != null) backButton.setVisible(false);
        if (signUpStatusLabel != null) signUpStatusLabel.setVisible(false);
    }

    private void loadBackgroundImages() {
        try {
            InputStream defaultStream = getClass().getResourceAsStream("/com/example/coderunlauncher/images/bg.png");
            InputStream loginStream = getClass().getResourceAsStream("/com/example/coderunlauncher/images/bg_login.png");
            InputStream signUpStream = getClass().getResourceAsStream("/com/example/coderunlauncher/images/forSignUp/bg_signup.png");

            if (defaultStream != null) defaultBg = new Image(defaultStream);
            if (loginStream != null) loginBg = new Image(loginStream);
            if (signUpStream != null) signUpBg = new Image(signUpStream);
        } catch (Exception e) {
            System.err.println("Note: Background images loading skipped or missing: " + e.getMessage());
        }
    }

    private void setupButtonHoverEffects() {
        addHoverEffect(loginButton);
        addHoverEffect(signUpButton);
        addHoverEffect(settingsButton);
        addHoverEffect(exitButton);
        addHoverEffect(githubButton);
        addHoverEffect(actionButton);
        addHoverEffect(backButton);
        addHoverEffect(sendCodeButton);
        addHoverEffect(registerButton);
        addHoverEffect(signUpToggleButton);
        addHoverEffect(resetSendButton);
        addHoverEffect(resetButton);
    }

    /**
     * OTP boxes: exactly one digit per box.
     * - Typing a digit replaces the box content and jumps to the next box.
     * - Letters/symbols are rejected.
     * - Backspace on an empty box jumps back; left/right arrows move between boxes.
     * - Pasting a whole code spreads the digits across the boxes.
     */
    private void setupOtpAutoAdvance() {
        wireOtp(new TextField[] { otp1, otp2, otp3, otp4, otp5, otp6 });
        TextField[] rc = { rc1, rc2, rc3, rc4, rc5, rc6 };
        wireOtp(rc);
        // The reset boxes feed the (hidden) resetCodeField the rest of the code reads
        for (TextField f : rc) {
            if (f == null) continue;
            f.textProperty().addListener((o, ov, nv) -> {
                StringBuilder sb = new StringBuilder();
                for (TextField x : rc) sb.append(x.getText());
                if (resetCodeField != null) resetCodeField.setText(sb.toString());
            });
        }
    }

    private void wireOtp(TextField[] otpFields) {
        for (int i = 0; i < otpFields.length; i++) {
            final TextField field = otpFields[i];
            if (field == null) continue;
            final int index = i;

            field.setTextFormatter(new TextFormatter<String>(change -> {
                if (!change.isContentChange()) return change;
                String incoming = change.getText();
                if (incoming.isEmpty()) return change;              // deleting is always allowed

                String digits = incoming.replaceAll("\\D", "");
                if (digits.isEmpty()) return null;                  // reject non-digits

                if (digits.length() == 1) {                         // one digit: replace the box content
                    change.setRange(0, change.getControlText().length());
                    change.setText(digits);
                    change.selectRange(1, 1);
                    if (!fillingOtp && index < otpFields.length - 1) {
                        Platform.runLater(() -> otpFields[index + 1].requestFocus());
                    }
                    return change;
                }

                // Several digits at once (paste): spread them over the boxes
                Platform.runLater(() -> fillOtpFrom(index, digits, otpFields));
                return null;
            }));

            field.setOnKeyPressed(e -> {
                if (e.getCode() == KeyCode.BACK_SPACE && field.getText().isEmpty() && index > 0) {
                    TextField prev = otpFields[index - 1];
                    prev.clear();
                    prev.requestFocus();
                } else if (e.getCode() == KeyCode.LEFT && index > 0) {
                    otpFields[index - 1].requestFocus();
                } else if (e.getCode() == KeyCode.RIGHT && index < otpFields.length - 1) {
                    otpFields[index + 1].requestFocus();
                }
            });

            // Select the box content on focus so the next digit simply overwrites it
            field.focusedProperty().addListener((obs, was, now) -> {
                if (now) Platform.runLater(field::selectAll);
            });
        }
    }

    private void fillOtpFrom(int start, String digits, TextField[] fields) {
        fillingOtp = true;
        try {
            int last = start;
            for (int j = 0; j < digits.length() && start + j < fields.length; j++) {
                fields[start + j].setText(String.valueOf(digits.charAt(j)));
                last = start + j;
            }
            fields[Math.min(last + 1, fields.length - 1)].requestFocus();
        } finally {
            fillingOtp = false;
        }
    }

    /**
     * Toggles password visibility using the eye icon on the sign-up form.
     * A plain TextField twin is created once, sits next to the PasswordField, and shares its text
     * (bidirectional binding). The toggle only flips which of the two is visible, so no node is ever
     * removed from the scene graph (the old swap approach lost the parent on the second click).
     */
    private void setupPasswordToggle() {
        if (eyeIcon == null || signUpPasswordField == null) return;
        if (!(signUpPasswordField.getParent() instanceof HBox row)) return;

        signUpPasswordTextField = new TextField();
        signUpPasswordTextField.setPromptText(signUpPasswordField.getPromptText());
        signUpPasswordTextField.setStyle(signUpPasswordField.getStyle());
        signUpPasswordTextField.setPrefHeight(signUpPasswordField.getPrefHeight());
        HBox.setHgrow(signUpPasswordTextField, Priority.ALWAYS);
        signUpPasswordTextField.textProperty().bindBidirectional(signUpPasswordField.textProperty());
        signUpPasswordTextField.setVisible(false);
        signUpPasswordTextField.setManaged(false);
        row.getChildren().add(row.getChildren().indexOf(signUpPasswordField) + 1, signUpPasswordTextField);

        eyeIcon.setCursor(Cursor.HAND);
        eyeIcon.setOnMouseClicked(event -> {
            signUpPasswordVisible = !signUpPasswordVisible;

            signUpPasswordTextField.setVisible(signUpPasswordVisible);
            signUpPasswordTextField.setManaged(signUpPasswordVisible);
            signUpPasswordField.setVisible(!signUpPasswordVisible);
            signUpPasswordField.setManaged(!signUpPasswordVisible);

            TextField active = signUpPasswordVisible ? signUpPasswordTextField : signUpPasswordField;
            active.requestFocus();
            active.positionCaret(active.getText().length());
        });
    }

    /** Fills the settings card and the "remember me" box from launcher.properties. */
    private void setupSettingsAndPrefs() {
        if (resolutionCombo != null) {
            resolutionCombo.getItems().setAll("Auto", "1280 x 720", "1600 x 900", "1920 x 1080");
            resolutionCombo.setValue(resolutionCombo.getItems().contains(settings.resolution) ? settings.resolution : "Auto");
        }
        if (fullscreenCheck != null) fullscreenCheck.setSelected(settings.fullscreen);
        if (closeOnLaunchCheck != null) closeOnLaunchCheck.setSelected(settings.closeOnLaunch);
        if (rememberCheck != null) rememberCheck.setSelected(settings.rememberUsername);
        if (usernameField != null && settings.rememberUsername) usernameField.setText(settings.username);
        if (loginSpinner != null) loginSpinner.managedProperty().bind(loginSpinner.visibleProperty());
    }

    /** Enter in the username box jumps to the password; Enter in the password box logs in. */
    private void setupEnterKeys() {
        // Centered fields: the caret of an empty field with prompt text sticks to the left edge,
        // so the prompt is drawn as an overlay label and the caret sits in the middle.
        centerPrompt(usernameField);
        centerPrompt(passwordField);
        centerPrompt(resetEmailField);
        centerPrompt(resetPasswordField);
        if (rc6 != null) rc6.setOnAction(e -> handleResetPassword());
        if (resetCodeField != null) {
            resetCodeField.setTextFormatter(new TextFormatter<String>(c ->
                    c.getControlNewText().matches("\\d{0,6}") ? c : null));
            resetCodeField.setOnAction(e -> handleResetPassword());
        }
        if (resetEmailField != null) resetEmailField.setOnAction(e -> handleResetSend());
        if (resetPasswordField != null) resetPasswordField.setOnAction(e -> handleResetPassword());

        if (usernameField != null && passwordField != null) {
            usernameField.setOnAction(e -> passwordField.requestFocus());
        }
        if (passwordField != null) passwordField.setOnAction(e -> handleAction());

        // Show/hide eye on the login and reset password planks (the sign-up form has its own)
        addEye(passwordField);
        addEye(resetPasswordField);
    }

    /** Intro: the menu buttons fly in one after another. */
    private void playIntro() {
        // The background stays perfectly still (a constantly zooming full-screen picture made the launcher shake).
        if (bgImageView != null) {
            bgImageView.setSmooth(true);
            bgImageView.setCache(true);
            bgImageView.setCacheHint(javafx.scene.CacheHint.SPEED);
        }

        Button[] items = { loginButton, signUpButton, settingsButton, exitButton };
        for (int i = 0; i < items.length; i++) {
            Button b = items[i];
            if (b == null) continue;
            b.setOpacity(0);
            b.setTranslateX(50);
            FadeTransition fade = new FadeTransition(Duration.millis(420), b);
            fade.setToValue(1);
            TranslateTransition slide = new TranslateTransition(Duration.millis(420), b);
            slide.setToX(0);
            slide.setInterpolator(Interpolator.EASE_OUT);
            ParallelTransition in = new ParallelTransition(fade, slide);
            in.setDelay(Duration.millis(250 + i * 110L));
            in.play();
        }
    }

    /** Cross-fades to a new background picture instead of swapping it abruptly. */
    private void crossfadeBackground(Image next) {
        if (next == null || bgImageView == null) return;
        if (bgFade != null) {
            bgFade.stop();
            if (bgOverlay != null && bgOverlay.getOpacity() > 0.5 && bgOverlay.getImage() != null) {
                bgImageView.setImage(bgOverlay.getImage());
            }
        }
        if (bgImageView.getImage() == next) return;

        if (bgOverlay == null && bgImageView.getParent() instanceof StackPane root) {
            bgOverlay = new ImageView();
            bgOverlay.setFitWidth(bgImageView.getFitWidth());
            bgOverlay.setFitHeight(bgImageView.getFitHeight());
            bgOverlay.setPreserveRatio(false);
            bgOverlay.setMouseTransparent(true);
            bgOverlay.setOpacity(0);
            bgOverlay.scaleXProperty().bind(bgImageView.scaleXProperty());
            bgOverlay.scaleYProperty().bind(bgImageView.scaleYProperty());
            root.getChildren().add(root.getChildren().indexOf(bgImageView) + 1, bgOverlay);
        }
        if (bgOverlay == null) {
            bgImageView.setImage(next);
            return;
        }
        bgOverlay.setImage(next);
        bgOverlay.setOpacity(0);
        bgFade = new FadeTransition(Duration.millis(320), bgOverlay);
        bgFade.setToValue(1);
        bgFade.setOnFinished(e -> {
            bgImageView.setImage(next);
            bgOverlay.setOpacity(0);
        });
        bgFade.play();
    }

    /** Quick horizontal shake, used when a login fails. */
    private void shake(Node node) {
        if (node == null) return;
        double base = node.getTranslateX();
        new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(node.translateXProperty(), base)),
                new KeyFrame(Duration.millis(60), new KeyValue(node.translateXProperty(), base - 10)),
                new KeyFrame(Duration.millis(120), new KeyValue(node.translateXProperty(), base + 10)),
                new KeyFrame(Duration.millis(180), new KeyValue(node.translateXProperty(), base - 7)),
                new KeyFrame(Duration.millis(240), new KeyValue(node.translateXProperty(), base + 7)),
                new KeyFrame(Duration.millis(300), new KeyValue(node.translateXProperty(), base))
        ).play();
    }

    /** Shows a message in a status label with a soft fade-in. */
    private void setStatus(Label label, String message, String color) {
        if (label == null) return;
        label.setStyle("-fx-text-fill: " + color + ";");
        label.setText(message);
        label.setVisible(true);
        label.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(180), label);
        fade.setToValue(1);
        fade.play();
    }

    /** Busy state of the login card: spinner on/off plus an optional message. */
    private void setBusy(boolean on, String message) {
        busy = on;
        if (loginSpinner != null) loginSpinner.setVisible(on);
        if (message != null) setStatus(statusLabel, message, INFO);
    }

    private void focusFirstField(Node shown) {
        if (shown == loginPane && usernameField != null && passwordField != null) {
            (usernameField.getText().isEmpty() ? usernameField : passwordField).requestFocus();
        } else if (shown == forgotPane && resetEmailField != null) {
            resetEmailField.requestFocus();
        } else if (shown == signUpPane && signUpUsernameField != null) {
            signUpUsernameField.requestFocus();
        }
    }

    private void addHoverEffect(Button button) {
        if (button == null) return;

        button.setCursor(Cursor.HAND);

        // Sign-up buttons are a transparent Button laid over an ImageView inside a StackPane.
        // Animate the whole StackPane so the artwork reacts, not just the invisible button.
        final Node target = (button.getGraphic() == null
                && button.getParent() instanceof StackPane sp
                && sp.getChildren().size() == 2
                && sp.getChildren().get(0) instanceof ImageView)
                ? sp : button;

        button.setOnMouseEntered(event -> {
            animateScale(target, 1.06);
            target.setOpacity(0.9);
        });
        button.setOnMouseExited(event -> {
            animateScale(target, 1.0);
            target.setOpacity(1.0);
        });
        button.setOnMousePressed(event -> {
            animateScale(target, 0.95);
            target.setOpacity(0.7);
        });
        button.setOnMouseReleased(event -> {
            animateScale(target, button.isHover() ? 1.06 : 1.0);
            target.setOpacity(button.isHover() ? 0.9 : 1.0);
        });
    }

    private void animateScale(Node node, double to) {
        Object previous = node.getProperties().get("hoverAnim");
        if (previous instanceof ScaleTransition p) p.stop();

        ScaleTransition st = new ScaleTransition(Duration.millis(120), node);
        st.setToX(to);
        st.setToY(to);
        st.play();
        node.getProperties().put("hoverAnim", st);
    }

    // --- View Navigation Handlers ---

    @FXML
    private void showLoginView() {
        isLoginMode = true;
        //if (titleLabel != null) titleLabel.setText("CodeRun Login");
        if (actionButton != null) actionButton.setText("");
        if (toggleButton != null) toggleButton.setText("Don't have an account? Create one");
        if (statusLabel != null) { statusLabel.setText(""); statusLabel.setVisible(true); }
        if (backButton != null) backButton.setVisible(true);

        // Hide sign-up pane if visible, show login pane
        if (signUpPane != null && signUpPane.isVisible()) {
            switchView(signUpPane, loginPane, loginBg);
        } else if (forgotPane != null && forgotPane.isVisible()) {
            switchView(forgotPane, loginPane, loginBg);
        } else {
            switchView(mainMenuPane, loginPane, loginBg);
        }
    }

    @FXML
    private void showSignUpView() {
        isLoginMode = false;
        clearSignUpFields();
        if (signUpStatusLabel != null) { signUpStatusLabel.setText(""); signUpStatusLabel.setVisible(false); }
        if (backButton != null) backButton.setVisible(true);

        // Hide main menu or login pane, show sign-up pane with its background
        if (loginPane != null && loginPane.isVisible()) {
            switchView(loginPane, signUpPane, signUpBg);
        } else {
            switchView(mainMenuPane, signUpPane, signUpBg);
        }
    }

    @FXML
    private void showMainMenuView() {
        if (forgotPane != null && forgotPane.isVisible()) {   // Back from "forgot password" returns to login
            showLoginView();
            return;
        }
        if (statusLabel != null) { statusLabel.setText(""); statusLabel.setVisible(false); }
        if (backButton != null) backButton.setVisible(false);
        if (usernameField != null) usernameField.setText(settings.rememberUsername ? settings.username : "");
        if (passwordField != null) passwordField.setText("");
        clearSignUpFields();
        generatedOtp = null;

        // Hide whichever pane is visible
        VBox from;
        if (signUpPane != null && signUpPane.isVisible()) from = signUpPane;
        else if (settingsPane != null && settingsPane.isVisible()) from = settingsPane;
        else from = loginPane;
        switchView(from, mainMenuPane, defaultBg);
    }

    /** Navigates from sign-up back to login. */
    @FXML
    private void handleBackToLogin() {
        showLoginView();
    }

    private void clearSignUpFields() {
        if (signUpUsernameField != null) signUpUsernameField.setText("");
        if (emailField != null) emailField.setText("");
        if (signUpPasswordField != null) signUpPasswordField.setText("");
        if (signUpPasswordTextField != null) signUpPasswordTextField.setText("");
        clearOtpFields();
    }

    private void clearOtpFields() {
        if (otp1 != null) otp1.setText("");
        if (otp2 != null) otp2.setText("");
        if (otp3 != null) otp3.setText("");
        if (otp4 != null) otp4.setText("");
        if (otp5 != null) otp5.setText("");
        if (otp6 != null) otp6.setText("");
    }

    private void switchView(VBox hidePane, VBox showPane, Image newBackground) {
        crossfadeBackground(newBackground);

        if (hidePane != null && showPane != null) {
            // remember each pane's designed x-offset so the slide always ends exactly there
            final double baseX = (Double) showPane.getProperties()
                    .computeIfAbsent("baseX", k -> showPane.getTranslateX());

            FadeTransition fadeOut = new FadeTransition(Duration.millis(150), hidePane);
            fadeOut.setFromValue(1.0);
            fadeOut.setToValue(0.0);
            fadeOut.setOnFinished(e -> {
                hidePane.setVisible(false);
                hidePane.setOpacity(1.0);
                showPane.setOpacity(0.0);
                showPane.setTranslateX(baseX + 28);
                showPane.setVisible(true);

                FadeTransition fadeIn = new FadeTransition(Duration.millis(240), showPane);
                fadeIn.setFromValue(0.0);
                fadeIn.setToValue(1.0);
                TranslateTransition slide = new TranslateTransition(Duration.millis(240), showPane);
                slide.setToX(baseX);
                slide.setInterpolator(Interpolator.EASE_OUT);
                ParallelTransition in = new ParallelTransition(fadeIn, slide);
                in.setOnFinished(ev -> focusFirstField(showPane));
                in.play();
            });
            fadeOut.play();
        } else if (showPane != null) {
            if (hidePane != null) hidePane.setVisible(false);
            showPane.setVisible(true);
        }
    }

    // --- Forgot password ---

    @FXML
    private void showForgotView() {
        if (resetEmailField != null) resetEmailField.clear();
        if (resetCodeField != null) resetCodeField.clear();
        for (TextField f : new TextField[] { rc1, rc2, rc3, rc4, rc5, rc6 }) if (f != null) f.clear();
        if (resetPasswordField != null) resetPasswordField.clear();
        if (resetStatusLabel != null) resetStatusLabel.setText("");
        resetOtp = null;
        if (backButton != null) backButton.setVisible(true);
        switchView(loginPane, forgotPane, loginBg);
    }

    /** Step 1: e-mail a 6-digit code - but only if that address belongs to an account. */
    @FXML
    private void handleResetSend() {
        String email = resetEmailField == null ? "" : resetEmailField.getText().trim();
        if (email.isEmpty() || !email.contains("@")) {
            setStatus(resetStatusLabel, "Enter the email you registered with.", ERR);
            shake(forgotPane);
            return;
        }
        if (System.currentTimeMillis() - lastResetSend < 30_000) {
            setStatus(resetStatusLabel, "Please wait a few seconds before asking for another code.", ERR);
            return;
        }
        final String[] creds = loadMailCredentials();
        if (creds == null) {
            setStatus(resetStatusLabel, "Email sender not set up. See mail.properties.example.", ERR);
            return;
        }

        lastResetSend = System.currentTimeMillis();
        if (resetSendButton != null) resetSendButton.setDisable(true);
        setStatus(resetStatusLabel, "Sending code...", INFO);

        Thread worker = new Thread(() -> {
            String error = null;
            String otp = null;
            try {
                boolean exists;
                try (Connection conn = DatabaseManager.getConnection();
                     PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE email = ?")) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        exists = rs.next();
                    }
                }
                if (exists) {
                    otp = String.format("%06d", new SecureRandom().nextInt(1_000_000));
                    sendEmail(creds, email, "CodeRun 2.0 - Password reset code",
                            "Someone asked to reset the password of your CodeRun 2.0 account.\n\n"
                            + "Your 6-digit code is: " + otp
                            + "\n\nIt expires in 10 minutes. If this wasn't you, just ignore this email.");
                }
            } catch (SQLException e) {
                error = "Database Error: " + e.getMessage();
            } catch (AuthenticationFailedException e) {
                error = "Mail login rejected. Check sender email / App Password.";
            } catch (Exception e) {
                e.printStackTrace();
                error = "Failed to send email. Check connection.";
            }

            final String fError = error;
            final String fOtp = otp;
            Platform.runLater(() -> {
                if (resetSendButton != null) resetSendButton.setDisable(false);
                if (fError != null) {
                    lastResetSend = 0;
                    setStatus(resetStatusLabel, fError, ERR);
                    return;
                }
                if (fOtp != null) {
                    resetOtp = fOtp;
                    resetEmail = email;
                    resetExpiresAt = System.currentTimeMillis() + 10 * 60 * 1000L;
                    resetAttempts = 0;
                }
                // Same message whether or not the address exists, so nobody can probe for accounts
                setStatus(resetStatusLabel, "If that email is registered, a code is on its way (valid 10 min).", OK);
                if (rc1 != null) rc1.requestFocus();
            });
        }, "reset-send-thread");
        worker.setDaemon(true);
        worker.start();
    }

    /** Step 2: check the code and store the new password. */
    @FXML
    private void handleResetPassword() {
        String email = resetEmailField == null ? "" : resetEmailField.getText().trim();
        String code = resetCodeField == null ? "" : resetCodeField.getText().trim();
        String pass = resetPasswordField == null ? "" : resetPasswordField.getText().trim();

        if (email.isEmpty() || code.isEmpty() || pass.isEmpty()) {
            setStatus(resetStatusLabel, "Fill in the email, the code and a new password.", ERR);
            shake(forgotPane);
            return;
        }
        if (resetOtp == null) {
            setStatus(resetStatusLabel, "Ask for a code first (Send code).", ERR);
            shake(forgotPane);
            return;
        }
        if (System.currentTimeMillis() > resetExpiresAt) {
            resetOtp = null;
            setStatus(resetStatusLabel, "That code expired. Send a new one.", ERR);
            shake(forgotPane);
            return;
        }
        if (!email.equalsIgnoreCase(resetEmail)) {
            setStatus(resetStatusLabel, "Email changed since the code was sent. Send a new code.", ERR);
            shake(forgotPane);
            return;
        }
        if (!resetOtp.equals(code)) {
            resetAttempts++;
            if (resetAttempts >= 5) {
                resetOtp = null;
                setStatus(resetStatusLabel, "Too many wrong codes. Send a new code.", ERR);
            } else {
                setStatus(resetStatusLabel, "Wrong code. " + (5 - resetAttempts) + " tries left.", ERR);
            }
            shake(forgotPane);
            return;
        }

        if (resetButton != null) resetButton.setDisable(true);
        setStatus(resetStatusLabel, "Saving new password...", INFO);

        Thread worker = new Thread(() -> {
            String error = null;
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE users SET password = ? WHERE email = ?")) {
                ps.setString(1, BCrypt.withDefaults().hashToString(12, pass.toCharArray()));
                ps.setString(2, email);
                if (ps.executeUpdate() == 0) error = "No account uses that email.";
            } catch (SQLException e) {
                error = "Database Error: " + e.getMessage();
            }
            final String fError = error;
            Platform.runLater(() -> {
                if (resetButton != null) resetButton.setDisable(false);
                if (fError != null) {
                    setStatus(resetStatusLabel, fError, ERR);
                    shake(forgotPane);
                    return;
                }
                resetOtp = null;
                setStatus(resetStatusLabel, "Password changed! You can log in now.", OK);
                PauseTransition wait = new PauseTransition(Duration.millis(1300));
                wait.setOnFinished(e -> showLoginView());
                wait.play();
            });
        }, "reset-save-thread");
        worker.setDaemon(true);
        worker.start();
    }

    /** Sends a plain-text mail through the Gmail sender configured in mail.properties. */
    private void sendEmail(String[] creds, String to, String subject, String text) throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.ssl.trust", "smtp.gmail.com");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(creds[0], creds[1]);
            }
        });
        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(creds[0], "CodeRun 2.0 Security"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);
        message.setText(text);
        Transport.send(message);
    }

    // --- Settings ---

    @FXML
    private void showSettingsView() {
        if (backButton != null) backButton.setVisible(true);
        if (fullscreenCheck != null) fullscreenCheck.setSelected(settings.fullscreen);
        if (closeOnLaunchCheck != null) closeOnLaunchCheck.setSelected(settings.closeOnLaunch);
        if (resolutionCombo != null) resolutionCombo.setValue(settings.resolution);
        if (settingsStatusLabel != null) settingsStatusLabel.setText("");
        switchView(mainMenuPane, settingsPane, defaultBg);
    }

    @FXML
    private void handleSaveSettings() {
        if (fullscreenCheck != null) settings.fullscreen = fullscreenCheck.isSelected();
        if (closeOnLaunchCheck != null) settings.closeOnLaunch = closeOnLaunchCheck.isSelected();
        if (resolutionCombo != null && resolutionCombo.getValue() != null) settings.resolution = resolutionCombo.getValue();
        settings.save();
        setStatus(settingsStatusLabel, "Saved! Applies the next time the game starts.", OK);
    }

    /** "1280 x 720" -> {1280, 720}; "Auto" (or anything unreadable) -> null. */
    private static int[] parseResolution(String text) {
        if (text == null) return null;
        String[] parts = text.toLowerCase().split("x");
        if (parts.length != 2) return null;
        try {
            return new int[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- Action Handlers for Custom Buttons ---

    @FXML
    private void handleExit() {
        Platform.exit();
        System.exit(0);
    }

    @FXML
    private void handleGitHubLink() {
        try {
            String url = "https://github.com";
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("win")) {
                Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler " + url);
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec("open " + url);
            } else { // Linux (Arch)
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Exception e) {
            if (statusLabel != null) {
                statusLabel.setText("Could not open browser link.");
            }
        }
    }

    // --- Send Code Button Logic ---

    @FXML
    private void handleSendCode() {
        String email = (emailField != null) ? emailField.getText().trim() : "";
        Label activeStatusLabel = getActiveStatusLabel();

        if (email.isEmpty() || !email.contains("@")) {
            activeStatusLabel.setStyle("-fx-text-fill: red;");
            activeStatusLabel.setVisible(true);
            activeStatusLabel.setText("Please enter a valid email address.");
            return;
        }

        // Sender account comes from env vars or an untracked mail.properties (never hardcode it)
        final String[] creds = loadMailCredentials();
        if (creds == null) {
            activeStatusLabel.setStyle("-fx-text-fill: red;");
            activeStatusLabel.setVisible(true);
            activeStatusLabel.setText("Email sender not set up. See mail.properties.example.");
            return;
        }
        final String senderEmail = creds[0];
        final String appPassword = creds[1];

        // Random 6-digit OTP (only becomes valid once the email has actually been sent)
        final String otp = String.format("%06d", new SecureRandom().nextInt(1_000_000));

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");
        props.put("mail.smtp.ssl.trust", "smtp.gmail.com");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        Session session = Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(senderEmail, appPassword);
            }
        });

        activeStatusLabel.setStyle("-fx-text-fill: white;");
        activeStatusLabel.setVisible(true);
        activeStatusLabel.setText("Sending code...");
        if (sendCodeButton != null) sendCodeButton.setDisable(true);

        Thread sender = new Thread(() -> {
            try {
                Message message = new MimeMessage(session);
                message.setFrom(new InternetAddress(senderEmail, "CodeRun 2.0 Security"));
                message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(email));
                message.setSubject("CodeRun 2.0 - Verification Code");
                message.setText("Welcome to CodeRun 2.0!\n\nYour 6-digit verification code is: " + otp
                        + "\n\nIt expires in 10 minutes. If you didn't request this, ignore this email.");

                Transport.send(message);

                Platform.runLater(() -> {
                    generatedOtp = otp;
                    otpEmail = email;
                    otpExpiresAt = System.currentTimeMillis() + 10 * 60 * 1000L;
                    activeStatusLabel.setStyle("-fx-text-fill: #00ff7f;");
                    activeStatusLabel.setText("Code sent to " + email);
                });
            } catch (Exception e) {
                e.printStackTrace();
                final String msg;
                if (e instanceof AuthenticationFailedException) {
                    msg = "Mail login rejected. Check sender email / App Password.";
                } else if (e instanceof SendFailedException) {
                    msg = "Couldn't send to that email address.";
                } else {
                    msg = "Failed to send email. Check connection.";
                }
                Platform.runLater(() -> {
                    activeStatusLabel.setStyle("-fx-text-fill: red;");
                    activeStatusLabel.setText(msg);
                });
            } finally {
                Platform.runLater(() -> {
                    if (sendCodeButton != null) sendCodeButton.setDisable(false);
                });
            }
        }, "send-otp-thread");
        sender.setDaemon(true);
        sender.start();
    }

    /**
     * Reads the sender account from CODERUN_MAIL_USER / CODERUN_MAIL_PASSWORD, or from
     * mail.properties (keys: mail.user, mail.password) in the working directory.
     * Returns null if nothing usable is configured.
     */
    private String[] loadMailCredentials() {
        String user = System.getenv("CODERUN_MAIL_USER");
        String pass = System.getenv("CODERUN_MAIL_PASSWORD");

        if (user == null || user.isBlank() || pass == null || pass.isBlank()) {
            Path file = AppPaths.config("mail.properties");
            if (Files.isReadable(file)) {
                Properties p = new Properties();
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                } catch (IOException e) {
                    return null;
                }
                user = p.getProperty("mail.user");
                pass = p.getProperty("mail.password");
            }
        }

        if (user == null || user.isBlank() || pass == null || pass.isBlank()
                || user.trim().equals("your_email@gmail.com")) {
            return null;
        }
        // Gmail shows App Passwords in 4 groups with spaces; the spaces aren't part of the password
        return new String[] { user.trim(), pass.replace(" ", "").trim() };
    }

    /** Returns the status label for the currently active view. */
    private Label getActiveStatusLabel() {
        if (signUpPane != null && signUpPane.isVisible() && signUpStatusLabel != null) {
            return signUpStatusLabel;
        }
        return statusLabel;
    }

    // --- Existing DB & Authentication Logic ---

    private void createTableIfNotExists() {
        String sql = "CREATE TABLE IF NOT EXISTS users (" +
                "id INT AUTO_INCREMENT PRIMARY KEY, " +
                "username VARCHAR(50) UNIQUE NOT NULL, " +
                "email VARCHAR(255) UNIQUE, " +
                "password VARCHAR(255) NOT NULL, " +
                "role VARCHAR(20) DEFAULT 'PLAYER')";
        try (Connection conn = DatabaseManager.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);

            // Older versions of the table had no email column - add it if it's missing
            try (ResultSet cols = conn.getMetaData().getColumns(conn.getCatalog(), null, "users", "email")) {
                if (!cols.next()) {
                    stmt.execute("ALTER TABLE users ADD COLUMN email VARCHAR(255) UNIQUE");
                }
            }
        } catch (SQLException e) {
            if (DatabaseManager.noPermission(e)) return;   // limited player login: the table already exists
            final String detail = e.getMessage();
            Platform.runLater(() -> setStatus(statusLabel, "DB Init Error: " + detail, ERR));
        }
    }

    @FXML
    private void toggleMode() {
        isLoginMode = !isLoginMode;
        if (statusLabel != null) statusLabel.setText("");

        if (isLoginMode) {
            //if (titleLabel != null) titleLabel.setText("CodeRun Login");
            if (actionButton != null) actionButton.setText("Login");
            if (toggleButton != null) toggleButton.setText("Don't have an account? Create one");
        } else {
            // Switch to the dedicated sign-up pane
            showSignUpView();
        }
    }

    @FXML
    private void handleAction() {
        if (usernameField == null || passwordField == null) return;

        String user = usernameField.getText().trim();
        String pass = passwordField.getText().trim();

        if (busy) return;

        if (user.isEmpty() || pass.isEmpty()) {
            setStatus(statusLabel, "Username and Password are required.", ERR);
            shake(loginPane);
            return;
        }

        if (isLoginMode) {
            performLogin(user, pass);
        }
    }

    /** Handles the "Complete Registration" button on the sign-up pane. */
    @FXML
    private void handleSignUpAction() {
        String username = (signUpUsernameField != null) ? signUpUsernameField.getText().trim() : "";
        // Get password from whichever field is currently visible
        String password;
        if (signUpPasswordVisible && signUpPasswordTextField != null) {
            password = signUpPasswordTextField.getText().trim();
        } else if (signUpPasswordField != null) {
            password = signUpPasswordField.getText().trim();
        } else {
            password = "";
        }
        String email = (emailField != null) ? emailField.getText().trim() : "";

        if (username.isEmpty() || password.isEmpty()) {
            if (signUpStatusLabel != null) {
                signUpStatusLabel.setStyle("-fx-text-fill: red;");
                signUpStatusLabel.setVisible(true);
                signUpStatusLabel.setText("Username and Password are required.");
            }
            return;
        }

        if (email.isEmpty() || !email.contains("@")) {
            if (signUpStatusLabel != null) {
                signUpStatusLabel.setStyle("-fx-text-fill: red;");
                signUpStatusLabel.setVisible(true);
                signUpStatusLabel.setText("Please enter a valid email address.");
            }
            return;
        }

        performRegister(username, password, email);
    }

    /** Checks the password on a background thread (network + BCrypt are slow) and reports back to the UI. */
    private void performLogin(String username, String password) {
        setBusy(true, "Signing in...");

        Thread worker = new Thread(() -> {
            boolean verified = false;
            String role = null;
            String error = null;

            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement("SELECT password, role FROM users WHERE username = ?")) {
                pstmt.setString(1, username);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        role = rs.getString("role");
                        verified = BCrypt.verifyer().verify(password.toCharArray(), rs.getString("password")).verified;
                    }
                }
            } catch (SQLException e) {
                error = "Database Error: " + e.getMessage();
            }

            final boolean ok = verified;
            final String fRole = role;
            final String fError = error;
            Platform.runLater(() -> onLoginResult(username, ok, fRole, fError));
        }, "login-thread");
        worker.setDaemon(true);
        worker.start();
    }

    private void onLoginResult(String username, boolean ok, String role, String error) {
        if (error != null) {
            setBusy(false, null);
            setStatus(statusLabel, error, ERR);
            shake(loginPane);
            return;
        }
        if (!ok) {
            setBusy(false, null);
            setStatus(statusLabel, "Invalid username or password.", ERR);
            shake(loginPane);
            if (passwordField != null) {
                passwordField.clear();
                passwordField.requestFocus();
            }
            return;
        }

        // Remember the username (never the password) if the player asked for it
        settings.rememberUsername = rememberCheck != null && rememberCheck.isSelected();
        settings.username = settings.rememberUsername ? username : "";
        settings.save();

        if ("ADMIN".equals(role)) {
            try {
                Launcher.showAdmin((Stage) actionButton.getScene().getWindow());
                busy = false;
            } catch (IOException e) {
                setBusy(false, null);
                setStatus(statusLabel, "Error loading Admin UI: " + e.getMessage(), ERR);
            }
        } else {
            setBusy(true, "Welcome, " + username + "! Launching CodeRun 2.0...");
            launchUnityGame(username);
        }
    }

    private void performRegister(String username, String password, String email) {
        // Combine the 6 OTP boxes into one string
        String enteredOtp = (otp1 != null ? otp1.getText() : "")
                          + (otp2 != null ? otp2.getText() : "")
                          + (otp3 != null ? otp3.getText() : "")
                          + (otp4 != null ? otp4.getText() : "")
                          + (otp5 != null ? otp5.getText() : "")
                          + (otp6 != null ? otp6.getText() : "");

        Label activeStatusLabel = getActiveStatusLabel();

        // 1. Check if OTP matches
        if (generatedOtp == null || !generatedOtp.equals(enteredOtp)) {
            activeStatusLabel.setStyle("-fx-text-fill: red;");
            activeStatusLabel.setVisible(true);
            activeStatusLabel.setText("Invalid or missing verification code.");
            return;
        }
        if (System.currentTimeMillis() > otpExpiresAt) {
            generatedOtp = null;
            activeStatusLabel.setStyle("-fx-text-fill: red;");
            activeStatusLabel.setVisible(true);
            activeStatusLabel.setText("Verification code expired. Send a new one.");
            return;
        }
        if (otpEmail == null || !otpEmail.equalsIgnoreCase(email)) {
            activeStatusLabel.setStyle("-fx-text-fill: red;");
            activeStatusLabel.setVisible(true);
            activeStatusLabel.setText("Email changed since the code was sent. Send a new code.");
            return;
        }

        activeStatusLabel.setStyle("-fx-text-fill: white;");
        activeStatusLabel.setVisible(true);
        activeStatusLabel.setText("Creating account...");
        if (registerButton != null) registerButton.setDisable(true);

        // 2. Hash password & insert into the Aiven DB off the UI thread (BCrypt + network are slow)
        Thread worker = new Thread(() -> {
            String sql = "INSERT INTO users (username, email, password, role) VALUES (?, ?, ?, 'PLAYER')";

            // Check first: a failed INSERT still uses up an AUTO_INCREMENT number (that is what made ids jump)
            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement check = conn.prepareStatement(
                         "SELECT username, email FROM users WHERE username = ? OR email = ?")) {
                check.setString(1, username);
                check.setString(2, email);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        final boolean nameTaken = username.equalsIgnoreCase(rs.getString("username"));
                        Platform.runLater(() -> {
                            setStatus(activeStatusLabel, nameTaken ? "Username already exists." : "Email is already registered.", ERR);
                            if (registerButton != null) registerButton.setDisable(false);
                        });
                        return;
                    }
                }
            } catch (SQLException e) {
                final String detail = e.getMessage();
                Platform.runLater(() -> {
                    setStatus(activeStatusLabel, "Database Error: " + detail, ERR);
                    if (registerButton != null) registerButton.setDisable(false);
                });
                return;
            }

            String hashedPassword = BCrypt.withDefaults().hashToString(12, password.toCharArray());

            try (Connection conn = DatabaseManager.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setString(1, username);
                pstmt.setString(2, email);
                pstmt.setString(3, hashedPassword);
                pstmt.executeUpdate();

                Platform.runLater(() -> {
                    activeStatusLabel.setStyle("-fx-text-fill: #00ff7f;");
                    activeStatusLabel.setVisible(true);
                    activeStatusLabel.setText("Registration complete! You can now log in.");

                    // Clear OTP so it can't be reused
                    generatedOtp = null;
                    otpEmail = null;
                    clearOtpFields();
                });

                // Navigate back to login after a short delay
                try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
                Platform.runLater(this::showLoginView);

            } catch (SQLException e) {
                e.printStackTrace();
                final String detail = e.getMessage() == null ? "" : e.getMessage();
                Platform.runLater(() -> {
                    activeStatusLabel.setStyle("-fx-text-fill: red;");
                    activeStatusLabel.setVisible(true);
                    if (detail.contains("users.username")) {
                        activeStatusLabel.setText("Username already exists.");
                    } else if (detail.contains("users.email")) {
                        activeStatusLabel.setText("Email is already registered.");
                    } else {
                        activeStatusLabel.setText("Database Error: " + detail);
                    }
                });
            } finally {
                Platform.runLater(() -> {
                    if (registerButton != null) registerButton.setDisable(false);
                });
            }
        }, "register-thread");
        worker.setDaemon(true);
        worker.start();
    }

    // --- Trivia questions (Open Trivia DB -> Aiven) ---

    /**
     * Makes sure the trivia_questions table has a healthy stock of questions. Runs in the background
     * (the Open Trivia DB rate limit makes it take several seconds) so login is never delayed.
     * Failures are ignored on purpose: the game still has questions from earlier fetches.
     */
    private void startTriviaTopUp() {
        Thread topUp = new Thread(() -> {
            try {
                TriviaService.topUp(100);
            } catch (Exception e) {
                System.err.println("Trivia top-up skipped: " + e.getMessage());
            }
        }, "trivia-topup");
        topUp.setDaemon(true);
        topUp.start();
    }

    // --- Unity Process Launcher ---

    /**
     * Exports a fresh set of questions, then starts the game with the player's name, the questions
     * file and the display options from the settings card. Runs off the UI thread because the
     * question export talks to the database.
     */
    private void launchUnityGame(String username) {
        Thread launcher = new Thread(() -> {
            try {
                boolean windows = System.getProperty("os.name").toLowerCase().contains("win");

                // A random set of questions for this session; if that fails the game
                // simply falls back to its built-in questions.
                Path questionsFile = AppPaths.runtimeDir().resolve("trivia_questions.json");
                boolean haveQuestions = false;
                try {
                    haveQuestions = TriviaService.exportRandom(50, questionsFile) > 0;
                } catch (Exception e) {
                    System.err.println("Could not export trivia questions: " + e.getMessage());
                }

                String exeName = windows ? "CodeRun.exe" : "CodeRun.x86_64";
                // Prefer game_client/windows or game_client/linux; fall back to a build placed directly in game_client/
                Path exe = AppPaths.gameDir().resolve(windows ? "windows" : "linux").resolve(exeName);
                if (!Files.exists(exe)) exe = AppPaths.gameDir().resolve(exeName);
                if (!Files.exists(exe)) {
                    Platform.runLater(() -> {
                        setBusy(false, null);
                        setStatus(statusLabel, "Game files not found in the game_client folder.", ERR);
                        shake(loginPane);
                    });
                    return;
                }
                if (!windows) exe.toFile().setExecutable(true); // copying a build can drop the exec bit

                // Saved progress: the game reads profile.json and leaves requests in the outbox (see GameBridge)
                GameBridge.prepare(username);

                List<String> command = new ArrayList<>(List.of(exe.toString(), "-username", username));
                command.add("-bridge");
                command.add(GameBridge.folder().toString());
                if (haveQuestions) {
                    command.add("-questions");
                    command.add(questionsFile.toAbsolutePath().toString());
                }
                command.add("-screen-fullscreen");
                command.add(settings.fullscreen ? "1" : "0");
                int[] size = parseResolution(settings.resolution);
                if (size != null) {
                    command.add("-screen-width");
                    command.add(String.valueOf(size[0]));
                    command.add("-screen-height");
                    command.add(String.valueOf(size[1]));
                }

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.directory(exe.getParent().toFile());
                // Nobody reads the game's stdout/stderr; on Windows the pipe buffer fills up and the
                // game freezes. Send everything to a log file instead.
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.to(
                        AppPaths.runtimeDir().resolve("game_output.log").toFile()));
                Process game = pb.start();
                GameBridge.watch(game, username, () -> Platform.runLater(() -> {
                    if (hiddenForGame) {
                        Platform.exit();
                        System.exit(0);
                    } else {
                        setStatus(statusLabel, "Game closed - your progress is saved.", OK);
                    }
                }));

                Platform.runLater(this::finishLaunch);

            } catch (Exception e) {
                final String detail = e.getMessage();
                Platform.runLater(() -> {
                    setBusy(false, null);
                    setStatus(statusLabel, "Failed to start game client: " + detail, ERR);
                    shake(loginPane);
                });
            }
        }, "game-launch-thread");
        launcher.setDaemon(true);
        launcher.start();
    }

    /** After the game process started: fade the launcher out and quit, or stay open (setting). */
    private void finishLaunch() {
        if (!settings.closeOnLaunch) {
            setBusy(false, null);
            setStatus(statusLabel, "Game started. Have fun!", OK);
            return;
        }
        Node root = (loginPane != null && loginPane.getScene() != null) ? loginPane.getScene().getRoot() : null;
        if (root == null) {
            Platform.exit();
            System.exit(0);
            return;
        }
        FadeTransition fade = new FadeTransition(Duration.millis(450), root);
        fade.setToValue(0);
        SequentialTransition closing = new SequentialTransition(new PauseTransition(Duration.millis(700)), fade);
        closing.setOnFinished(e -> {
            // Hide instead of quitting: the launcher keeps saving progress in the background
            // and closes by itself when the game closes.
            Platform.setImplicitExit(false);
            hiddenForGame = true;
            root.getScene().getWindow().hide();
        });
        closing.play();
    }

    private void centerPrompt(TextField f) {
        if (f == null || !(f.getParent() instanceof javafx.scene.layout.StackPane sp)) return;
        Label l = new Label(f.getPromptText());
        f.setPromptText("");
        l.setMouseTransparent(true);
        l.setFont(f.getFont());
        l.setStyle("-fx-text-fill: #7a5c43;");
        l.visibleProperty().bind(f.textProperty().isEmpty());
        sp.getChildren().add(l);
    }

    /** Puts the same eye icon as the sign-up form on a password plank: click to show or hide the password. */
    private void addEye(PasswordField pf) {
        if (pf == null || !(pf.getParent() instanceof javafx.scene.layout.StackPane sp)) return;

        TextField plain = new TextField();
        plain.setStyle(pf.getStyle());
        plain.setFont(pf.getFont());
        plain.setAlignment(pf.getAlignment());
        plain.setPrefWidth(pf.getPrefWidth());
        plain.setMaxWidth(pf.getMaxWidth());
        plain.setPrefHeight(pf.getPrefHeight());
        plain.setVisible(false);
        plain.setManaged(false);
        plain.textProperty().bindBidirectional(pf.textProperty());
        plain.setOnAction(e -> pf.fireEvent(new javafx.event.ActionEvent()));

        ImageView eye = new ImageView(new javafx.scene.image.Image(
                getClass().getResource("images/forSignUp/icon_eye.png").toExternalForm()));
        eye.setFitWidth(22);
        eye.setFitHeight(22);
        eye.setPreserveRatio(true);
        eye.setCursor(Cursor.HAND);
        StackPane.setAlignment(eye, javafx.geometry.Pos.CENTER_RIGHT);
        StackPane.setMargin(eye, new javafx.geometry.Insets(0, 14, 0, 0));

        eye.setOnMouseClicked(e -> {
            boolean show = !plain.isVisible();
            plain.setVisible(show);
            plain.setManaged(show);
            pf.setVisible(!show);
            pf.setManaged(!show);
            eye.setOpacity(show ? 0.55 : 1.0);
            TextField active = show ? plain : pf;
            active.requestFocus();
            active.positionCaret(active.getText().length());
        });
        sp.getChildren().addAll(plain, eye);
    }
}

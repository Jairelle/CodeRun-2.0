package com.example.coderunlauncher;

import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.fxml.FXMLLoader;
import javafx.scene.Group;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.IOException;

public class Launcher extends Application {

    /** Design size of the launcher artwork; everything is scaled from this to fit the window. */
    private static final double DESIGN_W = 800;
    private static final double DESIGN_H = 450;

    @Override
    public void start(Stage stage) throws IOException {
        Font.loadFont(getClass().getResourceAsStream("/com/example/coderunlauncher/images/fonts/Childos-Arabic-Font.otf"), 14);
        showLauncher(stage);
        stage.show();
    }

    /** Shows the login / main menu screen, full screen (the 800x450 layout is scaled to fit). */
    public static void showLauncher(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(Launcher.class.getResource("launcher-view.fxml"));
        Parent content = loader.load();

        Group scaled = new Group(content);
        StackPane holder = new StackPane(scaled);
        holder.setStyle("-fx-background-color: #0b141a;");
        DoubleBinding scale = Bindings.createDoubleBinding(
                () -> Math.max(0.1, Math.min(holder.getWidth() / DESIGN_W, holder.getHeight() / DESIGN_H)),
                holder.widthProperty(), holder.heightProperty());
        scaled.scaleXProperty().bind(scale);
        scaled.scaleYProperty().bind(scale);

        Scene scene = new Scene(holder, DESIGN_W, DESIGN_H);
        scene.getStylesheets().add(Launcher.class.getResource("launcher.css").toExternalForm());
        install(stage, scene, "CodeRun 2.0 Launcher", DESIGN_W, DESIGN_H);
    }

    /** Shows the admin dashboard (same theme as the launcher), full screen. */
    public static void showAdmin(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(Launcher.class.getResource("admin-view.fxml"));
        Scene scene = new Scene(loader.load(), 960, 580);
        scene.getStylesheets().add(Launcher.class.getResource("launcher.css").toExternalForm());
        install(stage, scene, "CodeRun 2.0 - Admin Dashboard", 900, 540);
    }

    /** Puts a scene in the window and goes full screen. F11 toggles, Esc leaves full screen. */
    private static void install(Stage stage, Scene scene, String title, double minW, double minH) {
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.F11) stage.setFullScreen(!stage.isFullScreen());
        });
        stage.setTitle(title);
        try {
            java.io.InputStream icon = Launcher.class.getResourceAsStream("/com/example/coderunlauncher/images/app_icon.png");
            if (icon != null && stage.getIcons().isEmpty()) stage.getIcons().add(new javafx.scene.image.Image(icon));
        } catch (Exception ignored) { }
        stage.setResizable(true);
        stage.setMinWidth(minW);
        stage.setMinHeight(minH);
        stage.setScene(scene);
        stage.setFullScreenExitHint("Press F11 to toggle full screen");
        stage.setFullScreen(true);
    }

    public static void main(String[] args) {
        launch(args);
    }
}

package com.example.coderunlauncher;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The small "Update" button in the top-right corner of the launcher.
 *
 * On start it asks GitHub for the newest release of Jairelle/CodeRun-2.0. If that version is newer than this
 * launcher (the version jpackage stamped into the installed app), the button appears. Clicking it:
 *   Windows - downloads the new Setup.exe, starts it and closes the launcher (the installer replaces the old version).
 *   Linux   - downloads the new .tar.gz, unpacks it over this installation and restarts the launcher.
 * If anything goes wrong it opens the release page in the browser instead.
 * When running from IntelliJ there is no installed version, so nothing is shown
 * (start with -Dcoderun.version=2.0.0 to try the button anyway).
 */
public final class UpdateChecker {

    private static final String REPO = "Jairelle/CodeRun-2.0";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";

    private final StackPane root;
    private final Button button = new Button();
    private String latestTag, pageUrl, assetUrl, assetName;
    private boolean busy;

    private UpdateChecker(StackPane root) { this.root = root; }

    /** Adds the (hidden) button to the launcher window and checks GitHub in the background. */
    public static void install(StackPane root) {
        if (root == null) return;
        new UpdateChecker(root).start();
    }

    /** This launcher's version, e.g. "2.0.2" (null when not installed with jpackage). */
    public static String currentVersion() {
        String v = System.getProperty("jpackage.app-version");
        if (v == null) v = System.getProperty("coderun.version");
        return v;
    }

    private void start() {
        button.setVisible(false);
        button.setManaged(false);
        button.setFocusTraversable(false);
        button.setStyle(
                "-fx-background-color: #2f9e5a; -fx-background-radius: 14; -fx-text-fill: white;" +
                "-fx-font-weight: bold; -fx-font-size: 12px; -fx-padding: 4 14 4 14; -fx-cursor: hand;" +
                "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 6, 0, 0, 2);");
        StackPane.setAlignment(button, Pos.TOP_RIGHT);
        StackPane.setMargin(button, new Insets(10, 12, 0, 0));
        button.setOnAction(e -> onClick());
        root.getChildren().add(button);

        String current = currentVersion();
        if (current == null) return;
        Thread t = new Thread(() -> check(current), "update-check");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ checking

    private void check(String current) {
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "CodeRunLauncher")
                    .timeout(java.time.Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return;
            JsonObject rel = JsonParser.parseString(res.body()).getAsJsonObject();
            String tag = rel.get("tag_name").getAsString();
            if (compare(tag, current) <= 0) return;           // already up to date

            boolean windows = isWindows();
            JsonArray assets = rel.getAsJsonArray("assets");
            for (JsonElement el : assets) {
                JsonObject a = el.getAsJsonObject();
                String name = a.get("name").getAsString().toLowerCase();
                boolean fits = windows ? name.endsWith(".exe") : (name.endsWith(".tar.gz") && name.contains("linux"));
                if (fits) {
                    assetName = a.get("name").getAsString();
                    assetUrl = a.get("browser_download_url").getAsString();
                    break;
                }
            }
            latestTag = tag;
            pageUrl = rel.get("html_url").getAsString();
            Platform.runLater(this::show);
        } catch (Exception e) {
            System.err.println("Update check: " + e.getMessage());   // offline etc. - just no button
        }
    }

    private void show() {
        button.setText("Update to " + latestTag);
        button.setTooltip(new Tooltip("A new version of CodeRun is out. Click to update."));
        button.setVisible(true);
        button.setManaged(true);
        button.toFront();
        FadeTransition pulse = new FadeTransition(Duration.seconds(1.1), button);
        pulse.setFromValue(1.0);
        pulse.setToValue(0.65);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(Animation.INDEFINITE);
        pulse.play();
    }

    /** Compares "v2.0.10" style versions: negative if a < b, 0 if equal, positive if a > b. */
    static int compare(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    // ------------------------------------------------------------------ updating

    private void onClick() {
        if (busy) return;
        if (assetUrl == null) { openPage(); return; }
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION,
                "Download and install CodeRun " + latestTag + " now?\nThe launcher will close and start again when it's done.",
                ButtonType.YES, ButtonType.NO);
        ask.setTitle("Update CodeRun");
        ask.setHeaderText("A new version is available");
        Optional<ButtonType> r = ask.showAndWait();
        if (r.isEmpty() || r.get() != ButtonType.YES) return;

        busy = true;
        button.setText("Downloading...");
        Thread t = new Thread(this::download, "update-download");
        t.setDaemon(true);
        t.start();
    }

    private void download() {
        try {
            Path dir = Files.createTempDirectory("coderun-update");
            Path file = dir.resolve(assetName);
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(assetUrl)).header("User-Agent", "CodeRunLauncher").GET().build();
            HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) throw new IllegalStateException("download failed (HTTP " + res.statusCode() + ")");
            long total = res.headers().firstValueAsLong("Content-Length").orElse(-1L);
            try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(file)) {
                byte[] buf = new byte[1 << 16];
                long done = 0;
                int n, lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        int pct = (int) (done * 100 / total);
                        if (pct != lastPct) {
                            lastPct = pct;
                            final int p = pct;
                            Platform.runLater(() -> button.setText("Downloading " + p + "%"));
                        }
                    }
                }
            }
            Platform.runLater(() -> button.setText("Installing..."));
            if (isWindows()) runWindowsInstaller(file); else installLinux(file, dir);
        } catch (Exception e) {
            System.err.println("Update failed: " + e.getMessage());
            Platform.runLater(() -> {
                busy = false;
                button.setText("Update to " + latestTag);
                Alert a = new Alert(Alert.AlertType.WARNING,
                        "The update could not be installed automatically:\n" + e.getMessage() +
                        "\n\nThe download page will open so you can install it yourself.");
                a.setHeaderText("Update failed");
                a.showAndWait();
                openPage();
            });
        }
    }

    // Windows: start the new installer and get out of its way
    private void runWindowsInstaller(Path setup) throws Exception {
        new ProcessBuilder("cmd", "/c", "start", "\"\"", setup.toString()).start();
        Platform.runLater(() -> { Platform.exit(); System.exit(0); });
    }

    // Linux: the release is a folder "CodeRun/" in a .tar.gz. Unpack it and copy it over this installation.
    private void installLinux(Path archive, Path tmp) throws Exception {
        String appPath = System.getProperty("jpackage.app-path");          // .../CodeRun/bin/CodeRun
        if (appPath == null) throw new IllegalStateException("can't find where CodeRun is installed");
        Path launcher = Path.of(appPath);
        Path appRoot = launcher.getParent().getParent();                    // .../CodeRun
        run(tmp, "tar", "-xzf", archive.toString(), "-C", tmp.toString());
        Path fresh = tmp.resolve("CodeRun");
        if (!Files.isDirectory(fresh)) throw new IllegalStateException("the download has an unexpected layout");
        // --remove-destination: replace files instead of writing into them (this launcher is still running)
        run(tmp, "cp", "-a", "--remove-destination", fresh + "/.", appRoot.toString());
        new ProcessBuilder(launcher.toString()).directory(appRoot.toFile()).start();
        Platform.runLater(() -> { Platform.exit(); System.exit(0); });
    }

    private static void run(Path dir, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new IllegalStateException(cmd[0] + " failed: " + output.trim());
    }

    private void openPage() {
        String url = pageUrl != null ? pageUrl : "https://github.com/" + REPO + "/releases/latest";
        try {
            if (isWindows()) new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            else new ProcessBuilder("xdg-open", url).start();
        } catch (Exception e) {
            System.err.println("Could not open the browser: " + e.getMessage());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

package com.example.coderunlauncher;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where things live, both when running from IntelliJ and when installed:
 *
 *   installDir()  the folder the launcher was installed into (read-only on Windows "Program Files").
 *                 Holds game_client/ (the Unity game) and config/ (bundled db.properties, mail.properties).
 *   dataDir()     a per-user writable folder for settings, saved-progress bridge files and exported questions:
 *                   Windows  %APPDATA%\CodeRun
 *                   Linux    ~/.local/share/CodeRun   (or $XDG_DATA_HOME/CodeRun)
 *                   macOS    ~/Library/Application Support/CodeRun
 *                 When running from the project folder (development), the project folder is used, like before.
 */
public final class AppPaths {

    private AppPaths() {}

    private static Path install, data;

    /** True when started from the IntelliJ project (pom.xml in the working folder). */
    public static boolean devMode() {
        return Files.exists(Path.of("pom.xml").toAbsolutePath());
    }

    public static synchronized Path installDir() {
        if (install != null) return install;
        Path cwd = Path.of("").toAbsolutePath();
        try {
            Path code = Path.of(AppPaths.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path dir = Files.isDirectory(code) ? code : code.getParent();
            // installed: the jar sits next to game_client/ (jpackage puts both in the app folder)
            for (Path p = dir; p != null; p = p.getParent()) {
                if (Files.isDirectory(p.resolve("game_client"))) { install = p; return install; }
                if (p.getNameCount() < dir.getNameCount() - 3) break;
            }
        } catch (URISyntaxException | SecurityException | NullPointerException ignored) {
            // fall through
        }
        install = cwd;
        return install;
    }

    public static synchronized Path dataDir() {
        if (data != null) return data;
        if (devMode()) { data = Path.of("").toAbsolutePath(); return data; }
        String os = System.getProperty("os.name", "").toLowerCase();
        String home = System.getProperty("user.home");
        Path d;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            d = Path.of(appData != null && !appData.isBlank() ? appData : home, "CodeRun");
        } else if (os.contains("mac")) {
            d = Path.of(home, "Library", "Application Support", "CodeRun");
        } else {
            String xdg = System.getenv("XDG_DATA_HOME");
            d = (xdg != null && !xdg.isBlank()) ? Path.of(xdg, "CodeRun") : Path.of(home, ".local", "share", "CodeRun");
        }
        try { Files.createDirectories(d); } catch (Exception ignored) {}
        data = d;
        return data;
    }

    /** The Unity game folder (contains linux/ and windows/). */
    public static Path gameDir() { return installDir().resolve("game_client"); }

    /**
     * Writable folder for files exchanged with the game (bridge/, trivia_questions.json).
     * Development: game_client/ like before. Installed: the per-user data folder.
     */
    public static Path runtimeDir() { return devMode() ? gameDir() : dataDir(); }

    /**
     * A config file (db.properties, mail.properties). Looks in the user's data folder first (so a player or
     * admin can override it), then the bundled config/ folder of the install, then next to the launcher.
     */
    public static Path config(String name) {
        Path[] candidates = {
                dataDir().resolve(name),
                installDir().resolve("config").resolve(name),
                installDir().resolve(name),
                Path.of(name).toAbsolutePath()
        };
        for (Path p : candidates) if (Files.isReadable(p)) return p;
        return candidates[0];
    }
}

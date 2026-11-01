package com.example.coderunlauncher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Small preferences file (launcher.properties next to the launcher).
 * Only harmless options and, if the player ticks "Remember my username", the username.
 * Passwords are never stored.
 */
public final class LauncherSettings {

    private static final Path FILE = AppPaths.dataDir().resolve("launcher.properties");

    public boolean fullscreen = false;
    public String resolution = "Auto";
    public boolean closeOnLaunch = true;
    public boolean rememberUsername = false;
    public String username = "";

    public static LauncherSettings load() {
        LauncherSettings s = new LauncherSettings();
        if (Files.isReadable(FILE)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(FILE)) {
                p.load(in);
                s.fullscreen = Boolean.parseBoolean(p.getProperty("fullscreen", "false"));
                s.resolution = p.getProperty("resolution", "Auto");
                s.closeOnLaunch = Boolean.parseBoolean(p.getProperty("closeOnLaunch", "true"));
                s.rememberUsername = Boolean.parseBoolean(p.getProperty("rememberUsername", "false"));
                s.username = p.getProperty("username", "");
            } catch (IOException ignored) {
                // fall back to defaults
            }
        }
        return s;
    }

    public void save() {
        Properties p = new Properties();
        p.setProperty("fullscreen", String.valueOf(fullscreen));
        p.setProperty("resolution", resolution);
        p.setProperty("closeOnLaunch", String.valueOf(closeOnLaunch));
        p.setProperty("rememberUsername", String.valueOf(rememberUsername));
        p.setProperty("username", rememberUsername ? username : "");
        try (OutputStream out = Files.newOutputStream(FILE)) {
            p.store(out, "CodeRun launcher settings");
        } catch (IOException e) {
            System.err.println("Could not save launcher settings: " + e.getMessage());
        }
    }
}

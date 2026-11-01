package com.example.coderunlauncher;

/**
 * Entry point for the installed app. JavaFX refuses to start when the main class itself extends Application
 * and JavaFX is on the class path (as it is in the packaged app), so this plain class starts Launcher.
 */
public final class Main {
    public static void main(String[] args) {
        Launcher.main(args);
    }
}

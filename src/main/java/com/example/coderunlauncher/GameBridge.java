package com.example.coderunlauncher;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Connects the running game to the database without giving the game any database password.
 *
 *   game_client/bridge/profile.json   launcher -> game: wallet, skins, upgrades, stats, leaderboard
 *   game_client/bridge/outbox/*.json  game -> launcher: island results and shop requests, one file each
 *
 * While the game runs, this checks the outbox every second, saves each request through ProgressService,
 * deletes the file and writes a fresh profile.json (the game reloads it when it changes).
 * Requests left over from a session where the launcher was closed are handled at the next launch.
 */
public final class GameBridge {

    private GameBridge() {}

    public static Path folder() { return AppPaths.runtimeDir().resolve("bridge"); }

    private static Path outbox() { return folder().resolve("outbox"); }

    private static int version;
    private static int messageId;

    /** Before the game starts: handle leftovers, then write the player's profile. */
    public static void prepare(String username) {
        try {
            Files.createDirectories(outbox());
        } catch (IOException e) {
            System.err.println("Bridge folder: " + e.getMessage());
        }
        processOutbox(username);
        writeProfile(username, "");
    }

    /** Watches the outbox until the game closes, then runs onExit (on this background thread). */
    public static void watch(Process game, String username, Runnable onExit) {
        Thread t = new Thread(() -> {
            long lastRefresh = System.currentTimeMillis();
            while (game.isAlive()) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                boolean changed = processOutbox(username);
                // keep the leaderboard fresh even when this player does nothing
                if (!changed && System.currentTimeMillis() - lastRefresh > 45_000) {
                    writeProfile(username, null);
                    lastRefresh = System.currentTimeMillis();
                }
            }
            processOutbox(username);   // anything sent right before quitting
            if (onExit != null) onExit.run();
        }, "game-bridge");
        t.setDaemon(false);            // keeps the launcher alive (hidden) until saving is finished
        t.start();
    }

    /** Handles every waiting request in order. Returns true if anything was processed. */
    private static synchronized boolean processOutbox(String username) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(outbox())) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(files::add);
        } catch (IOException e) {
            return false;
        }
        if (files.isEmpty()) return false;

        String lastMessage = "";
        boolean dbError = false;
        for (Path f : files) {
            try {
                String text = Files.readString(f, StandardCharsets.UTF_8);
                JsonObject evt = JsonParser.parseString(text).getAsJsonObject();
                String msg = ProgressService.apply(username, evt);
                if (msg != null && !msg.isEmpty()) lastMessage = msg;
                Files.deleteIfExists(f);
            } catch (java.sql.SQLException e) {
                // database not reachable: keep the file and try again later
                lastMessage = "Could not reach the save server - will retry.";
                dbError = true;
                break;
            } catch (Exception e) {
                // unreadable request: drop it so it does not block the others
                System.err.println("Bad bridge request " + f.getFileName() + ": " + e.getMessage());
                try { Files.deleteIfExists(f); } catch (IOException ignored) {}
            }
        }
        writeProfile(username, lastMessage);
        return !dbError;
    }

    /**
     * Writes profile.json. message: text for the game to show (null = no new message).
     * Writes to a temp file first, so the game never reads a half-written file.
     */
    private static synchronized void writeProfile(String username, String message) {
        JsonObject o;
        try {
            o = ProgressService.profileJson(username);
        } catch (Exception e) {
            o = new JsonObject();
            o.addProperty("username", username);
            o.addProperty("online", false);
            if (message == null || message.isEmpty()) message = "Offline - progress cannot be saved right now.";
        }
        o.addProperty("version", ++version);
        if (message != null && !message.isEmpty()) messageId++;
        o.addProperty("messageId", messageId);
        o.addProperty("message", message == null ? "" : message);
        try {
            Path dir = folder();
            Files.createDirectories(dir);
            Path tmp = dir.resolve("profile.json.tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("profile.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.writeString(folder().resolve("profile.json"), o.toString(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {}
        }
    }
}

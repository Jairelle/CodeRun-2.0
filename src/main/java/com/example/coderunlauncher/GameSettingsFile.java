package com.example.coderunlauncher;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * In-game settings shared with the game (volume, music, mouse sensitivity, first-person view).
 *
 *   game_client/bridge/settings.json   (same folder as profile.json, passed to the game with -bridge)
 *
 * The game reads this file when it starts and writes it whenever the player changes something in the
 * in-game Esc menu; the launcher's Settings screen reads and writes the same file. So a change made in
 * either place shows up in the other. Field names must match GameSettings.cs in the Unity project.
 */
public final class GameSettingsFile {

    public double masterVolume = 0.8;      // 0..1
    public double musicVolume = 0.7;       // 0..1
    public double mouseSensitivity = 1.0;  // 0.25..2
    public boolean firstPerson = false;

    private static Path file() { return GameBridge.folder().resolve("settings.json"); }

    /** Reads settings.json (defaults if it is missing or unreadable). */
    public static GameSettingsFile load() {
        GameSettingsFile s = new GameSettingsFile();
        Path f = file();
        if (!Files.isReadable(f)) return s;
        try {
            JsonObject o = JsonParser.parseString(Files.readString(f, StandardCharsets.UTF_8)).getAsJsonObject();
            if (o.has("masterVolume")) s.masterVolume = o.get("masterVolume").getAsDouble();
            if (o.has("musicVolume")) s.musicVolume = o.get("musicVolume").getAsDouble();
            if (o.has("mouseSensitivity")) s.mouseSensitivity = o.get("mouseSensitivity").getAsDouble();
            if (o.has("firstPerson")) s.firstPerson = o.get("firstPerson").getAsBoolean();
        } catch (Exception e) {
            System.err.println("Could not read game settings, using defaults: " + e.getMessage());
        }
        s.clamp();
        return s;
    }

    /** Writes settings.json (creates the bridge folder if the game has never been started yet). */
    public void save() {
        clamp();
        JsonObject o = new JsonObject();
        o.addProperty("masterVolume", masterVolume);
        o.addProperty("musicVolume", musicVolume);
        o.addProperty("mouseSensitivity", mouseSensitivity);
        o.addProperty("firstPerson", firstPerson);
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), gson.toJson(o), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("Could not save game settings: " + e.getMessage());
        }
    }

    private void clamp() {
        masterVolume = Math.max(0, Math.min(1, masterVolume));
        musicVolume = Math.max(0, Math.min(1, musicVolume));
        mouseSensitivity = Math.max(0.25, Math.min(2, mouseSensitivity));
    }
}

package com.example.coderunlauncher;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.MouseEvent;
import javafx.scene.media.AudioClip;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.util.Duration;

import java.net.URL;

/**
 * Launcher sounds: a calm island music loop (sounds/launcher_music.wav) and a soft click on every button.
 * Volume = the Master and Music sliders of the Settings screen (the same settings the game uses).
 * The music fades out while the game is running and comes back when it closes.
 * If sound can't play on this computer, the launcher simply stays quiet.
 */
public final class LauncherAudio {

    private LauncherAudio() {}

    private static MediaPlayer music;
    private static AudioClip click;
    private static double master = 0.8, musicVol = 0.7;
    private static boolean paused;

    /** Starts the music (once) and adds button clicks to this scene. */
    public static void install(Scene scene) {
        try {
            if (music == null) {
                GameSettingsFile g = GameSettingsFile.load();
                master = g.masterVolume; musicVol = g.musicVolume;
                URL u = LauncherAudio.class.getResource("sounds/launcher_music.wav");
                if (u != null) {
                    music = new MediaPlayer(new Media(u.toExternalForm()));
                    music.setCycleCount(MediaPlayer.INDEFINITE);
                    music.setVolume(0);
                    music.setOnReady(() -> { music.play(); fadeTo(target(), 2.5); });
                    music.setOnError(() -> System.err.println("Launcher music: " + music.getError()));
                }
                URL c = LauncherAudio.class.getResource("sounds/click.wav");
                if (c != null) click = new AudioClip(c.toExternalForm());
            }
        } catch (Throwable t) {
            System.err.println("Launcher sound disabled: " + t);   // no audio on this machine
        }
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            Object t = e.getTarget();
            for (javafx.scene.Node n = t instanceof javafx.scene.Node ? (javafx.scene.Node) t : null; n != null; n = n.getParent())
                if (n instanceof ButtonBase) { playClick(); break; }
        });
    }

    public static void playClick() {
        if (click != null) click.play(Math.min(1.0, master * 0.6));
    }

    /** Live volume from the Settings sliders. */
    public static void setVolumes(double masterVolume, double musicVolume) {
        master = masterVolume; musicVol = musicVolume;
        if (music != null && !paused) music.setVolume(target());
    }

    /** The game started: fade the music out. */
    public static void pauseForGame() {
        paused = true;
        fadeTo(0, 1.2);
    }

    /** The game closed: bring the music back. */
    public static void resumeAfterGame() {
        paused = false;
        if (music != null) { music.play(); fadeTo(target(), 2.0); }
    }

    private static double target() { return Math.clamp(master * musicVol * 0.6, 0.0, 1.0); }

    private static void fadeTo(double v, double seconds) {
        if (music == null) return;
        Timeline t = new Timeline(new KeyFrame(Duration.seconds(seconds), new KeyValue(music.volumeProperty(), v)));
        if (v == 0) t.setOnFinished(e -> { if (paused) music.pause(); });
        t.play();
    }
}

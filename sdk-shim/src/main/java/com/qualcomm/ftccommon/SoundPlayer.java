package com.qualcomm.ftccommon;

import android.content.Context;

import org.firstinspires.ftc.robotcore.external.function.Consumer;
import org.ftctestbed.shim.SimHooks;

import java.io.File;

/**
 * Plays sounds on the Driver Station. In the simulator a sound file (.wav) plays on the computer;
 * sounds named by an Android resource ID can't be found (the simulator has no app resources),
 * so they play nothing, as a missing resource would.
 */
public class SoundPlayer {
    public static final String TAG = "SoundPlayer";
    public static boolean TRACE = false;
    public static final int msSoundTransmissionFreshness = 400;

    /** How to play a sound: volume 0-1, loopControl 0 = once (-1 = forever), rate 0.5-2. */
    public static class PlaySoundParams {
        public float volume = 1.0f;
        public boolean waitForNonLoopingSoundsToFinish = true;
        public int loopControl = 0;
        public float rate = 1.0f;

        public PlaySoundParams() {}

        public PlaySoundParams(boolean wait) {
            waitForNonLoopingSoundsToFinish = wait;
        }

        public PlaySoundParams(PlaySoundParams them) {
            volume = them.volume;
            waitForNonLoopingSoundsToFinish = them.waitForNonLoopingSoundsToFinish;
            loopControl = them.loopControl;
            rate = them.rate;
        }

        public boolean isLooping() {
            return loopControl == -1;
        }
    }

    private static final SoundPlayer INSTANCE = new SoundPlayer(3, 6);
    private volatile float masterVolume = 1.0f;

    public static SoundPlayer getInstance() {
        return INSTANCE;
    }

    public SoundPlayer(int simultaneousStreams, int cacheSize) {}

    public void close() {}

    public void prefillSoundCache(int... resourceIds) {}

    public boolean preload(Context context, int resourceId) {
        return false;
    }

    public boolean preload(Context context, File file) {
        return file != null && file.isFile();
    }

    public void startPlaying(Context context, int resId) {
        startPlaying(context, resId, new PlaySoundParams(), null, null);
    }

    public void startPlaying(Context context, File file) {
        startPlaying(context, file, new PlaySoundParams(), null, null);
    }

    public void startPlaying(Context context, int resId, PlaySoundParams params, Consumer<Integer> runWhenStarted, Runnable runWhenFinished) {
        if (TRACE) SimHooks.log("V", TAG, "no sound resource " + resId + " in the simulator");
        if (runWhenFinished != null) runWhenFinished.run();
    }

    public void startPlaying(Context context, File file, PlaySoundParams params, Consumer<Integer> runWhenStarted, Runnable runWhenFinished) {
        if (file == null || !file.isFile()) {
            SimHooks.log("W", TAG, "sound file not found: " + file);
            if (runWhenFinished != null) runWhenFinished.run();
            return;
        }
        if (runWhenStarted != null) runWhenStarted.accept(1);
        SimHooks.playSound(file, params.volume * masterVolume, params.loopControl, runWhenFinished);
    }

    public void play(Context context, int resId) { startPlaying(context, resId); }

    public void play(Context context, int resId, boolean waitForCompletion) { startPlaying(context, resId); }

    public void play(Context context, int resId, float volume, int loop, float rate) {
        PlaySoundParams p = new PlaySoundParams();
        p.volume = volume;
        p.loopControl = loop;
        p.rate = rate;
        startPlaying(context, resId, p, null, null);
    }

    public void play(Context context, File file, float volume, int loop, float rate) {
        PlaySoundParams p = new PlaySoundParams();
        p.volume = volume;
        p.loopControl = loop;
        p.rate = rate;
        startPlaying(context, file, p, null, null);
    }

    public void stopPlayingAll() {
        SimHooks.stopSounds();
    }

    public void stopPlayingLoops() {
        SimHooks.stopSounds();
    }

    public void setMasterVolume(float volume) {
        masterVolume = Math.max(0, Math.min(1, volume));
    }

    public float getMasterVolume() {
        return masterVolume;
    }

    public boolean isLocalSoundOn() {
        return true;
    }
}

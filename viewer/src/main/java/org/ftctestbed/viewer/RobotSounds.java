package org.ftctestbed.viewer;

import org.ftctestbed.shim.SimHooks;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;
import java.io.File;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Plays the sounds robot code starts with SoundPlayer (on a real robot they come out of the
 * Driver Station). Java can play .wav and .aiff files; anything else is noted in the Console.
 * Off in tests (testbed.noSound).
 */
final class RobotSounds implements SimHooks.SoundSink {
    private final List<Clip> playing = new CopyOnWriteArrayList<>();

    @Override
    public void play(File file, float volume, int loopControl, Runnable whenFinished) {
        if (Boolean.getBoolean("testbed.noSound")) {
            if (whenFinished != null) whenFinished.run();
            return;
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file)) {
            Clip clip = AudioSystem.getClip();
            clip.open(in);
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN) && volume < 1) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                gain.setValue((float) Math.max(gain.getMinimum(), 20 * Math.log10(Math.max(volume, 1e-4))));
            }
            clip.addLineListener(e -> {
                if (e.getType() == LineEvent.Type.STOP) {
                    playing.remove(clip);
                    clip.close();
                    if (whenFinished != null) whenFinished.run();
                }
            });
            playing.add(clip);
            // loopControl: 0 plays once, n plays n more times, -1 forever (as on Android's SoundPool).
            if (loopControl != 0) clip.loop(loopControl < 0 ? Clip.LOOP_CONTINUOUSLY : loopControl);
            else clip.start();
        } catch (Exception e) {
            System.out.println("Can't play " + file.getName() + " on this computer (" + e.getMessage() + "); .wav files work.");
            if (whenFinished != null) whenFinished.run();
        }
    }

    @Override
    public void stopAll() {
        for (Clip c : playing) c.stop();
    }
}

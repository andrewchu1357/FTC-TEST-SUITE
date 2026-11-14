package org.ftctestbed.viewer;

import org.ftctestbed.sim.game.MatchState;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * The match sounds a field plays, timed by the simulated match clock: the charge at the start
 * of AUTO, a buzzer when AUTO ends, "Drivers, pick up your controllers" and "three, two, one"
 * during the transition, the bell when TELEOP starts, the factory whistle with 30 seconds
 * left, the buzzer at the end, and a foghorn when a match is stopped early.
 *
 * The tones are made here (not FIRST's recordings). The voice lines are recordings in
 * resources/sounds; a copy without them plays plain beeps in their place.
 * Call update() regularly with where the match is; the sounds play on a background thread.
 */
public final class MatchSounds {
    public enum Cue { CHARGE, END_AUTO, PICK_UP, THREE, TWO, ONE, TELEOP_START, ENDGAME, END_MATCH, ABORT }

    static final float RATE = 44100;
    /** When each transition cue plays, in seconds after AUTO ends (TELEOP starts at 8). */
    static final double PICK_UP_AT = 1.2, THREE_AT = 5, TWO_AT = 6, ONE_AT = 7;
    /** The factory whistle: this long into TELEOP (30 s left). */
    static final double ENDGAME_AT = MatchState.TELEOP_SECONDS - 30;

    // One sound at a time; the thread ends when idle, so a closed window doesn't leave one behind.
    private final ExecutorService player = idleEnding(new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(), r -> {
        Thread t = new Thread(r, "match sounds");
        t.setDaemon(true);
        return t;
    }));

    private static java.util.concurrent.ThreadPoolExecutor idleEnding(java.util.concurrent.ThreadPoolExecutor e) {
        e.allowCoreThreadTimeOut(true);
        return e;
    }
    private final Map<Cue, float[]> sounds = new EnumMap<>(Cue.class);
    private volatile boolean enabled = true;
    private volatile boolean broken;
    private MatchState.Phase lastPhase = MatchState.Phase.PRACTICE;
    private double lastElapsed;

    public void setEnabled(boolean on) {
        enabled = on;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Where the match is now (any thread, but one at a time): plays whatever became due since the last call. */
    public synchronized void update(MatchState.Phase phase, double elapsedInPhase) {
        List<Cue> due = due(lastPhase, lastElapsed, phase, elapsedInPhase);
        lastPhase = phase;
        lastElapsed = elapsedInPhase;
        if (enabled) for (Cue c : due) play(c);
    }

    /**
     * The cues due between two looks at the match (phase and seconds into it, before and now).
     * A cue fires once, when its moment is crossed, so it doesn't matter how often you look or how
     * fast the simulation runs.
     */
    static List<Cue> due(MatchState.Phase was, double wasElapsed, MatchState.Phase now, double elapsed) {
        List<Cue> out = new ArrayList<>();
        boolean entered = was != now;
        double from = entered ? -1 : wasElapsed;
        if (entered) {
            switch (now) {
                case AUTO -> out.add(Cue.CHARGE);
                case TRANSITION -> out.add(Cue.END_AUTO);
                case TELEOP -> out.add(Cue.TELEOP_START);
                case ENDED -> out.add(Cue.END_MATCH);
                case PRACTICE -> {
                    // Stopped while the match was on (not after it had ended).
                    if (was == MatchState.Phase.AUTO || was == MatchState.Phase.TRANSITION || was == MatchState.Phase.TELEOP) out.add(Cue.ABORT);
                }
            }
        }
        if (now == MatchState.Phase.TRANSITION) {
            if (crossed(from, elapsed, PICK_UP_AT)) out.add(Cue.PICK_UP);
            if (crossed(from, elapsed, THREE_AT)) out.add(Cue.THREE);
            if (crossed(from, elapsed, TWO_AT)) out.add(Cue.TWO);
            if (crossed(from, elapsed, ONE_AT)) out.add(Cue.ONE);
        }
        if (now == MatchState.Phase.TELEOP && crossed(from, elapsed, ENDGAME_AT)) out.add(Cue.ENDGAME);
        return out;
    }

    private static boolean crossed(double from, double to, double at) {
        return from < at && to >= at;
    }

    // ------------------------------------------------------------------ playing

    private void play(Cue cue) {
        if (broken || Boolean.getBoolean("testbed.noSound")) return;
        player.submit(() -> {
            try {
                float[] samples = sound(cue);
                AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
                byte[] pcm = toPcm(samples);
                Clip clip = AudioSystem.getClip();
                clip.open(format, pcm, 0, pcm.length);
                clip.addLineListener(e -> {
                    if (e.getType() == LineEvent.Type.STOP) clip.close();
                });
                clip.start();
            } catch (Throwable e) {
                broken = true; // no sound device: stay quiet rather than retry every cue
                System.err.println("Match sounds are off: " + e);
            }
        });
    }

    /** The cue's samples (-1..1 at RATE), made or loaded on first use. */
    synchronized float[] sound(Cue cue) {
        return sounds.computeIfAbsent(cue, c -> level(switch (c) {
            case CHARGE -> charge();
            case END_AUTO -> buzzer(0.9);
            case PICK_UP -> voiceOr("pickup.wav", new double[][]{{523.25, 0.2}, {659.25, 0.2}, {783.99, 0.45}});
            case THREE -> voiceOr("three.wav", new double[][]{{880, 0.3}});
            case TWO -> voiceOr("two.wav", new double[][]{{880, 0.3}});
            case ONE -> voiceOr("one.wav", new double[][]{{1174.66, 0.45}});
            case TELEOP_START -> fireBell(1.7);
            case ENDGAME -> factoryWhistle(1.8);
            case END_MATCH -> buzzer(2.0);
            case ABORT -> foghorn(2.2);
        }));
    }

    /** All the sounds about equally loud (by their RMS while sounding), never clipping. */
    static float[] level(float[] s) {
        double sum = 0;
        int n = 0;
        for (float v : s) {
            if (Math.abs(v) > 0.01) {
                sum += v * v;
                n++;
            }
        }
        if (n == 0) return s;
        double gain = 0.16 / Math.sqrt(sum / n);
        float peak = 0;
        for (float v : s) peak = Math.max(peak, Math.abs(v));
        gain = Math.min(gain, 0.9 / peak);
        for (int i = 0; i < s.length; i++) s[i] *= (float) gain;
        return s;
    }

    static byte[] toPcm(float[] s) {
        byte[] b = new byte[s.length * 2];
        for (int i = 0; i < s.length; i++) {
            int v = Math.round(Math.max(-1f, Math.min(1f, s[i])) * 32767);
            b[2 * i] = (byte) v;
            b[2 * i + 1] = (byte) (v >> 8);
        }
        return b;
    }

    // ------------------------------------------------------------------ the sounds

    /** "Charge!", the cavalry bugle call (a traditional call, played on a synthesized bugle). */
    static float[] charge() {
        double[][] notes = { // frequency, seconds
                {392.00, 0.13}, {523.25, 0.13}, {659.25, 0.13}, {783.99, 0.36}, {659.25, 0.13}, {783.99, 0.95}};
        double total = 0;
        for (double[] n : notes) total += n[1];
        float[] out = new float[(int) (total * RATE) + 1];
        int at = 0;
        double phase = 0; // carried across notes, so they join without a click
        for (double[] n : notes) {
            int len = (int) (n[1] * RATE);
            for (int i = 0; i < len; i++) {
                double t = i / RATE;
                // Tongued attack, short release, a little vibrato on the long notes.
                double env = Math.min(1, t / 0.02) * Math.min(1, (n[1] - t) / 0.035);
                double f = n[0] * (1 + (n[1] > 0.3 ? 0.006 * Math.sin(2 * Math.PI * 5.5 * t) * Math.min(1, t / 0.25) : 0));
                phase += 2 * Math.PI * f / RATE;
                double bright = 0.55 + 0.45 * Math.min(1, t / 0.06);
                out[at + i] += (float) (0.32 * env * brass(phase, bright));
            }
            at += len;
        }
        return out;
    }

    /** A brassy tone at this phase: strong low harmonics, brighter as the note opens up. */
    private static double brass(double phase, double bright) {
        double[] h = {1, 0.75, 0.55, 0.38, 0.26, 0.17, 0.11, 0.07};
        double v = 0;
        for (int k = 0; k < h.length; k++) v += h[k] * Math.pow(bright, k) * Math.sin(phase * (k + 1));
        return v / 2.2;
    }

    /** The field buzzer: a rough, loud low tone. */
    static float[] buzzer(double seconds) {
        float[] out = new float[(int) (seconds * RATE)];
        double lp = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / RATE;
            double saw = 0;
            for (double f : new double[]{146, 149.5, 220}) saw += ((t * f) % 1.0) * 2 - 1;
            lp += (saw / 3 - lp) * 0.25; // take the harsh edge off
            double env = Math.min(1, t / 0.015) * Math.min(1, (seconds - t) / 0.06);
            out[i] = (float) (0.45 * env * lp);
        }
        return out;
    }

    /** A fire bell: a clapper striking a metal gong about 18 times a second. */
    static float[] fireBell(double seconds) {
        float[] out = new float[(int) ((seconds + 0.6) * RATE)];
        double[] partials = {1, 2.76, 5.40, 8.93}, amps = {1, 0.55, 0.3, 0.15}, decay = {0.5, 0.25, 0.12, 0.06};
        double f0 = 1150, strike = 1 / 18.0;
        for (double s = 0; s < seconds; s += strike) {
            int start = (int) (s * RATE);
            double hit = 0.75 + 0.25 * Math.sin(s * 37); // strikes aren't all alike
            for (int i = start; i < out.length; i++) {
                double t = (i - start) / RATE;
                if (t > 0.6) break;
                double v = 0;
                for (int k = 0; k < partials.length; k++) v += amps[k] * Math.exp(-t / decay[k]) * Math.sin(2 * Math.PI * f0 * partials[k] * t);
                out[i] += (float) (0.075 * hit * v);
            }
        }
        return out;
    }

    /** A factory steam whistle: three pipes sounding together, rising to pitch as the steam opens. */
    static float[] factoryWhistle(double seconds) {
        float[] out = new float[(int) (seconds * RATE)];
        double[] pipes = {466, 587, 698};
        double[] phase = new double[pipes.length];
        java.util.Random noise = new java.util.Random(7);
        double hiss = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / RATE;
            double rise = 0.86 + 0.14 * Math.min(1, t / 0.18), fall = t > seconds - 0.35 ? 1 - 0.06 * (t - (seconds - 0.35)) / 0.35 : 1;
            double env = Math.min(1, t / 0.08) * Math.min(1, (seconds - t) / 0.3);
            double v = 0;
            for (int k = 0; k < pipes.length; k++) {
                phase[k] += 2 * Math.PI * pipes[k] * rise * fall / RATE;
                v += Math.sin(phase[k]) + 0.18 * Math.sin(2 * phase[k]);
            }
            hiss += (noise.nextGaussian() - hiss) * 0.3;
            out[i] = (float) (env * (0.16 * v + 0.05 * hiss));
        }
        return out;
    }

    /** A foghorn: a deep tone that sags as it dies away. */
    static float[] foghorn(double seconds) {
        float[] out = new float[(int) (seconds * RATE)];
        double ph = 0;
        for (int i = 0; i < out.length; i++) {
            double t = i / RATE;
            double f = 98 * (t > seconds - 0.5 ? 1 - 0.12 * (t - (seconds - 0.5)) / 0.5 : 1);
            ph += 2 * Math.PI * f / RATE;
            double v = 0;
            for (int k = 1; k <= 6; k++) v += Math.sin(k * ph) / k;
            double env = Math.min(1, t / 0.12) * Math.min(1, (seconds - t) / 0.4);
            out[i] = (float) (0.42 * env * v);
        }
        return out;
    }

    /** A recorded voice line, or these beeps (frequency, seconds) when the recording isn't there. */
    static float[] voiceOr(String file, double[][] beeps) {
        float[] v = voice(file);
        return v.length > 0 ? v : beeps(beeps);
    }

    /** Plain beeps (frequency, seconds), a short gap after each: a sine with a touch of its octave. */
    static float[] beeps(double[][] notes) {
        double gap = 0.06, total = 0;
        for (double[] n : notes) total += n[1] + gap;
        float[] out = new float[(int) (total * RATE)];
        int at = 0;
        for (double[] n : notes) {
            int len = (int) (n[1] * RATE);
            for (int i = 0; i < len; i++) {
                double t = i / RATE;
                double env = Math.min(1, t / 0.008) * Math.min(1, (n[1] - t) / 0.05);
                out[at + i] = (float) (0.4 * env * (Math.sin(2 * Math.PI * n[0] * t) + 0.2 * Math.sin(4 * Math.PI * n[0] * t)));
            }
            at += len + (int) (gap * RATE);
        }
        return out;
    }

    /** A recorded voice line from resources/sounds, resampled to RATE and evened out in loudness; empty if there is none. */
    static float[] voice(String file) {
        try (InputStream raw = MatchSounds.class.getResourceAsStream("/sounds/" + file)) {
            if (raw == null) return new float[0];
            AudioInputStream in = AudioSystem.getAudioInputStream(new BufferedInputStream(raw));
            AudioFormat f = in.getFormat();
            byte[] data = in.readAllBytes();
            int frame = f.getFrameSize(), n = data.length / frame;
            float[] src = new float[n];
            for (int i = 0; i < n; i++) {
                int lo = data[i * frame] & 0xFF, hi = data[i * frame + 1];
                int v = f.isBigEndian() ? (lo << 8) | (hi & 0xFF) : (hi << 8) | lo;
                src[i] = (short) v / 32768f;
            }
            double ratio = f.getSampleRate() / RATE;
            float[] out = new float[(int) (n / ratio)];
            float peak = 1e-6f;
            for (int i = 0; i < out.length; i++) {
                double x = i * ratio;
                int k = (int) x;
                double a = x - k;
                out[i] = (float) (src[Math.min(k, n - 1)] * (1 - a) + src[Math.min(k + 1, n - 1)] * a);
                peak = Math.max(peak, Math.abs(out[i]));
            }
            for (int i = 0; i < out.length; i++) out[i] *= 0.7f / peak;
            return out;
        } catch (Exception e) {
            return new float[0];
        }
    }

}

package org.ftctestbed.viewer;

import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.game.MatchState.Phase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.ftctestbed.viewer.MatchSounds.Cue.*;
import static org.junit.jupiter.api.Assertions.*;

/** The field's match sounds come at the right moments of a match, once each, at any speed. */
class MatchSoundsTest {
    record Heard(MatchSounds.Cue cue, double matchTime) {}

    /** Plays a whole match (0.5 s practice first), looking every step seconds of match time. */
    static List<Heard> match(double step) {
        List<Heard> heard = new ArrayList<>();
        Phase was = Phase.PRACTICE;
        double wasE = 0;
        double total = 0.5 + MatchState.AUTO_SECONDS + MatchState.TRANSITION_SECONDS + MatchState.TELEOP_SECONDS + 3;
        for (double t = 0; t <= total; t += step) {
            Phase p;
            double e;
            double a = t - 0.5, tr = a - MatchState.AUTO_SECONDS, te = tr - MatchState.TRANSITION_SECONDS;
            if (a < 0) { p = Phase.PRACTICE; e = t; }
            else if (tr < 0) { p = Phase.AUTO; e = a; }
            else if (te < 0) { p = Phase.TRANSITION; e = tr; }
            else if (te < MatchState.TELEOP_SECONDS) { p = Phase.TELEOP; e = te; }
            else { p = Phase.ENDED; e = te - MatchState.TELEOP_SECONDS; }
            for (MatchSounds.Cue c : MatchSounds.due(was, wasE, p, e)) heard.add(new Heard(c, t - 0.5));
            was = p;
            wasE = e;
        }
        return heard;
    }

    @Test
    void aMatchSoundsLikeAMatch() {
        List<Heard> heard = match(1 / 60.0);
        assertEquals(List.of(CHARGE, END_AUTO, PICK_UP, THREE, TWO, ONE, TELEOP_START, ENDGAME, END_MATCH),
                heard.stream().map(Heard::cue).toList());
        double[] at = {0, 30, 31.2, 35, 36, 37, 38, 128, 158};
        for (int i = 0; i < at.length; i++) assertEquals(at[i], heard.get(i).matchTime(), 0.02, heard.get(i).toString());
    }

    @Test
    void theSameAtFourTimesSpeed() {
        // At 4x the window sees the match in bigger jumps: still every sound, once.
        assertEquals(match(1 / 60.0).stream().map(Heard::cue).toList(), match(4 / 60.0).stream().map(Heard::cue).toList());
    }

    @Test
    void pausedNothingRepeats() {
        assertTrue(MatchSounds.due(Phase.TELEOP, 95, Phase.TELEOP, 95).isEmpty());
        assertTrue(MatchSounds.due(Phase.TRANSITION, 5.5, Phase.TRANSITION, 5.5).isEmpty());
    }

    @Test
    void stoppingAMatchEarlySoundsTheFoghornButNotAfterItEnded() {
        assertEquals(List.of(ABORT), MatchSounds.due(Phase.TELEOP, 40, Phase.PRACTICE, 0));
        assertEquals(List.of(ABORT), MatchSounds.due(Phase.AUTO, 4, Phase.PRACTICE, 0));
        assertTrue(MatchSounds.due(Phase.ENDED, 3, Phase.PRACTICE, 0).isEmpty(), "back to practice after the match");
        assertTrue(MatchSounds.due(Phase.PRACTICE, 3, Phase.PRACTICE, 4).isEmpty(), "practice and the sandbox are quiet");
    }

    @Test
    void everySoundIsThereAndNotClipped() {
        MatchSounds s = new MatchSounds();
        for (MatchSounds.Cue c : MatchSounds.Cue.values()) {
            float[] a = s.sound(c);
            double seconds = a.length / MatchSounds.RATE;
            float peak = 0;
            for (float v : a) peak = Math.max(peak, Math.abs(v));
            assertTrue(seconds > 0.2 && seconds < 4, c + " lasts " + seconds + " s");
            assertTrue(peak > 0.2 && peak <= 1.0, c + " peak " + peak);
            assertEquals(a.length * 2, MatchSounds.toPcm(a).length);
        }
        assertTrue(s.sound(PICK_UP).length / MatchSounds.RATE > 1.5, "the whole sentence");
        assertTrue(MatchSounds.ONE_AT + s.sound(ONE).length / MatchSounds.RATE < MatchState.TRANSITION_SECONDS + 0.5,
                "\"one\" is over by the time TELEOP starts");
    }
}

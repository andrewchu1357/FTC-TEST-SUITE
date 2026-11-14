package org.ftctestbed.sim.season.biobuzz;

import org.ftctestbed.sim.game.MatchState;
import org.ftctestbed.sim.game.SeasonSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.ftctestbed.sim.season.biobuzz.BioBuzzSeason.*;

/** Everything the viewer needs to draw and score BIOBUZZ for one frame. */
public record BioBuzzSnapshot(
        List<HiveState> hives,
        List<FlowerState> flowers,
        Score red,
        Score blue,
        Map<String, Integer> humanPlayerNectar,
        Map<String, Integer> humanPlayerAllowed,
        List<String> notes,
        MatchState.Phase phase,
        double matchClock,
        boolean flowersUnlocked) implements SeasonSnapshot {

    /** tiltDegrees: angle of the far CELL above horizontal. fill: CELL weight / tip threshold. */
    /**
     * contents: colors of the pieces in the upward CELL; contentsAt: every piece in either CELL
     * (rolling out of one included) as {x across, u along, w up, radius, color} in the HIVE's own
     * frame (meters; see BioBuzzField.CELL_INNER); fill: the upward CELL's pull as a share of what
     * tips the HIVE.
     */
    public record HiveState(String alliance, double x, int upSide, double tiltDegrees, boolean tipping,
                            List<Integer> contents, int tipsAuto, int tipsTeleop, double fill, List<double[]> contentsAt) {}

    public record FlowerState(int index, double wallX, double wallY, double nx, double ny, double axisX, double axisY,
                              List<Integer> stack, String owner, int scored) {}

    /**
     * leaves, autoParks, teleopParks: how many of the alliance's robots earned each. cards: the
     * YELLOW / RED CARDS its robots' teams got this match, and for which rules.
     */
    public record Score(int leaves, int autoParks, int teleopParks, int tipsAuto, int tipsTeleop,
                        int cellContents, int ownedFlowerPieces, int bottomNectarBonuses, int garden, int foulPoints,
                        List<String> cards) {
        /** At least one robot of the alliance earned it. */
        public boolean leave() { return leaves > 0; }
        public boolean autoPark() { return autoParks > 0; }
        public boolean teleopPark() { return teleopParks > 0; }

        public int leavePoints() { return leaves * LEAVE; }
        public int parkPoints() { return (autoParks + teleopParks) * PARK; }
        public int tips() { return tipsAuto + tipsTeleop; }

        public int total() {
            return leavePoints() + parkPoints() + tips() * TIP + cellContents * CELL + ownedFlowerPieces * FLOWER_OWNED
                    + bottomNectarBonuses * BOTTOM_NECTAR + garden * GARDEN + foulPoints;
        }
    }

    public Score scoreOf(String alliance) {
        return alliance.equals("red") ? red : blue;
    }

    @Override
    public String title() {
        return "BIOBUZZ";
    }

    @Override
    public int score(String alliance) {
        return scoreOf(alliance).total();
    }

    @Override
    public List<String> breakdown(String alliance) {
        Score s = scoreOf(alliance);
        List<String> out = new ArrayList<>();
        out.add(line("LEAVE", s.leaves, LEAVE));
        out.add(line("PARK (auto)", s.autoParks, PARK));
        out.add(line("PARK (teleop)", s.teleopParks, PARK));
        out.add(line("HIVE TIPS (auto)", s.tipsAuto, TIP));
        out.add(line("HIVE TIPS (teleop)", s.tipsTeleop, TIP));
        out.add(line("In upward CELL", s.cellContents, CELL));
        out.add(line("In owned FLOWERS", s.ownedFlowerPieces, FLOWER_OWNED));
        out.add(line("Bottom NECTAR", s.bottomNectarBonuses, BOTTOM_NECTAR));
        out.add(line("In GARDEN", s.garden, GARDEN));
        if (s.foulPoints > 0) out.add(String.format(Locale.US, "%-20s      +%3d", "Opponent fouls", s.foulPoints));
        for (String c : s.cards) out.add(c.trim().replaceAll(" +", " "));
        return out;
    }

    private static String line(String label, int count, int each) {
        return String.format(Locale.US, "%-20s %2d x %2d = %3d", label, count, each, count * each);
    }

    @Override
    public List<String> rankingPoints(String alliance) {
        Score s = scoreOf(alliance);
        List<String> out = new ArrayList<>();
        int swarm = s.leavePoints() + s.parkPoints();
        out.add((swarm >= SWARM_POINTS ? "✓ " : "· ") + "SWARM (" + swarm + "/" + SWARM_POINTS + " leave+park)");
        out.add((s.tips() >= POLLINATOR_1_TIPS ? "✓ " : "· ") + "POLLINATOR 1 (" + s.tips() + "/" + POLLINATOR_1_TIPS + " tips)");
        out.add((s.tips() >= POLLINATOR_2_TIPS ? "✓ " : "· ") + "POLLINATOR 2 (" + s.tips() + "/" + POLLINATOR_2_TIPS + " tips)");
        return out;
    }

    @Override
    public List<String> notes() {
        return notes;
    }
}

package org.ftctestbed.viewer;

import org.ftctestbed.sim.SimHarness;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.ftctestbed.sim.opmode.OpModeRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The Robot 2 tab: the partner's own Driver Station. */
class RobotPanelTest {
    SimHarness h;
    RobotPanel panel;
    final List<String> log = new ArrayList<>();
    final List<Path> switchedTo = new ArrayList<>();
    boolean busy;

    @BeforeEach
    void setUp() throws Exception {
        h = SimHarness.loadWithPartner();
        SimHarness.Driver two = h.robot(2);
        OpModeRegistry.Result opModes = new OpModeRegistry.Result(new ArrayList<>(two.availableOpModes()), new ArrayList<>());
        Path robot = Path.of("config/robot.jsonc").toAbsolutePath();
        SwingUtilities.invokeAndWait(() -> panel = new RobotPanel(h.sim, two.robot, two.opModes, h.physics, opModes,
                PhysicalGamepads.shared(), () -> false, () -> busy, () -> true,
                List.of(robot, Path.of("robots/PedroQuickstart/robot.jsonc").toAbsolutePath()), robot,
                p -> p.getParent().getFileName().toString(), switchedTo::add, log::add));
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    static void onSwing(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
    }

    @Test
    void itDrivesThePartnersOwnOpMode() throws Exception {
        OpModeRunner two = h.robot(2).opModes;
        onSwing(() -> {
            for (int i = 0; i < panel.opModeBox.getItemCount(); i++) {
                if (panel.opModeBox.getItemAt(i).name().equals("BuzzBot TeleOp")) panel.opModeBox.setSelectedIndex(i);
            }
            assertEquals("Init", panel.runButton.getText());
            assertFalse(panel.stopButton.isVisible());
            panel.runButton.doClick();
        });
        h.runFor(0.5);
        assertEquals(OpModeRunner.State.INIT, two.state());
        assertEquals(OpModeRunner.State.IDLE, h.robot(1).opModes.state(), "robot 1 untouched");
        onSwing(() -> {
            assertEquals("Start", panel.runButton.getText(), "the button follows the state");
            assertTrue(panel.stopButton.isVisible(), "Stop without starting, beside it");
            panel.runButton.doClick();
        });
        h.runFor(0.5);
        assertEquals(OpModeRunner.State.RUNNING, two.state());
        onSwing(() -> {
            panel.tick();
            assertEquals("Stop", panel.runButton.getText());
            assertFalse(panel.stopButton.isVisible());
            assertEquals(String.join("\n", two.telemetry().publishedLines()), panel.telemetryArea.getText(), "its own telemetry");
        });
        assertFalse(panel.telemetryArea.getText().isEmpty());
        onSwing(() -> panel.runButton.doClick());
        h.runFor(0.1);
        assertNotEquals(OpModeRunner.State.RUNNING, two.state(), "Stop stopped it");
        h.robot(2).stop(); // waits (in real time, as the harness does) for the OpMode thread to finish
        assertEquals(OpModeRunner.State.STOPPED, two.state());
        assertEquals(OpModeRunner.State.IDLE, h.robot(1).opModes.state(), "robot 1 untouched");
    }

    @Test
    void itsStartPicksAnyStartAndSwapsWithRobot1() throws Exception {
        List<FieldConfig.StartPosition> starts = h.sim.fieldConfig.startPositions();
        onSwing(() -> {
            assertEquals("Red Audience", ((FieldConfig.StartPosition) panel.startBox.getSelectedItem()).name());
            panel.startBox.setSelectedItem(starts.get(2)); // Blue Wall: practice as an opponent
        });
        assertEquals("Blue Wall", h.robot(2).robot.startPosition().name());
        assertEquals("blue", h.robot(2).robot.alliance());
        onSwing(() -> panel.startBox.setSelectedItem(starts.get(0))); // Red Wall, where robot 1 is
        assertEquals("Red Wall", h.robot(2).robot.startPosition().name());
        assertEquals("Blue Wall", h.robot(1).robot.startPosition().name(), "the robots swapped");

        busy = true;
        onSwing(() -> panel.startBox.setSelectedItem(starts.get(1)));
        assertEquals("Red Wall", h.robot(2).robot.startPosition().name(), "not while an OpMode or match runs");
        onSwing(() -> assertEquals("Red Wall", ((FieldConfig.StartPosition) panel.startBox.getSelectedItem()).name()));
        assertTrue(log.stream().anyMatch(l -> l.contains("Stop the OpModes")), log.toString());

        // Robot 1 takes Red Wall back from its own panel: robot 2 swaps to Blue Wall and the tab catches up.
        busy = false;
        h.sim.setStartPosition(starts.get(0));
        assertEquals("Blue Wall", h.robot(2).robot.startPosition().name());
        onSwing(() -> {
            panel.tick();
            assertEquals("Blue Wall", ((FieldConfig.StartPosition) panel.startBox.getSelectedItem()).name());
        });
    }

    @Test
    void pickingAnotherRobotRelaunchesWithIt() throws Exception {
        onSwing(() -> panel.robotBox.setSelectedIndex(1));
        assertEquals(1, switchedTo.size());
        assertTrue(switchedTo.get(0).toString().contains("PedroQuickstart"));
        OpModeInfo tele = panel.teleOp();
        assertNotNull(tele);
        assertFalse(tele.autonomous());
    }
}

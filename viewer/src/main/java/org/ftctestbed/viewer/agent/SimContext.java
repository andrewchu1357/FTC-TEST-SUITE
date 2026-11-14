package org.ftctestbed.viewer.agent;

import com.qualcomm.robotcore.hardware.HardwareMap;

import org.ftctestbed.sim.SimRunner;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.game.MatchController;
import org.ftctestbed.sim.opmode.OpModeRegistry;
import org.ftctestbed.sim.opmode.OpModeRunner;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * One simulation an agent works with: a headless session it created (lockstep: time moves only
 * when a tool asks, as fast as the robot code allows, the same every run) or the simulator's
 * window (live: real time, as the person at the computer sees it). Robots are numbered from 1.
 */
public interface SimContext {
    String id();

    /** True for the window's simulation (real time; screenshots and view controls work). */
    boolean live();

    Simulation sim();

    SimRunner physics();

    MatchController match();

    int robots();

    OpModeRunner runner(int robot);

    OpModeRegistry.Result opModes(int robot);

    /** The robot's config file (a package's robot.jsonc, or config/robot.jsonc). */
    Path robotFile(int robot);

    Path fieldFile();

    /** The class loader the robot's team code was loaded in (snippets see its classes). */
    default ClassLoader codeLoader(int robot) {
        List<org.ftctestbed.sim.opmode.OpModeInfo> ops = opModes(robot).opModes();
        return ops.isEmpty() ? getClass().getClassLoader() : ops.get(0).type().getClassLoader();
    }

    default HardwareMap hardwareMap(int robot) {
        return sim().robot(robot).hardwareMap;
    }

    /**
     * Gives the robot's gamepad 1 or 2 this state, with press/release edges as the SDK reports
     * them (live: it replaces the controllers and keyboard for that gamepad until released).
     */
    default void setGamepad(int robot, int pad, com.qualcomm.robotcore.hardware.Gamepad state) {
        com.qualcomm.robotcore.hardware.Gamepad target = pad == 2 ? runner(robot).gamepad2 : runner(robot).gamepad1;
        state.id = target.id;
        target.simApply(state);
    }

    /** Back to a gamepad at rest (live: back to the controllers and keyboard). */
    default void releaseGamepad(int robot, int pad) {
        setGamepad(robot, pad, new com.qualcomm.robotcore.hardware.Gamepad());
    }

    /** Lets simulated time run for seconds (live: waits; when paused, steps the physics by hand). */
    void advance(double seconds);

    /** Lets time run until the condition holds, up to maxSeconds; true if it did. */
    boolean advanceUntil(BooleanSupplier condition, double maxSeconds);

    /** Stops the robot's OpMode and waits until it has stopped (a stuck one is given up on after a few seconds). */
    void stopOpMode(int robot);

    /**
     * Plays a whole match (field reset, AUTO, transition, TELEOP) with these OpModes, one per robot
     * (null: sits still), and returns once it has ended and the pieces have settled.
     */
    void runMatch(List<org.ftctestbed.sim.opmode.OpModeInfo> autos, List<org.ftctestbed.sim.opmode.OpModeInfo> teleops);

    /** Messages from setting it up: configs loaded, team code compiled, files left out. */
    List<String> messages();

    /** Extra facts for sim_list / sim_create. */
    Map<String, Object> describe();

    /** The window as it is now (live only). */
    default BufferedImage screenshot(String view) {
        throw new Json.ToolError("Screenshots are of the simulator's window: use the live session (the window must be open with agent access on).");
    }

    /** Shows a view or section in the window, picks a camera (live only). */
    default String control(Map<String, Object> request) {
        throw new Json.ToolError("Window controls work on the live session only.");
    }

    void close();
}

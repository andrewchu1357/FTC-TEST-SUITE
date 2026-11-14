package org.ftctestbed.viewer;

import com.qualcomm.robotcore.hardware.Gamepad;

import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.opmode.OpModeRunner;

import java.util.ArrayDeque;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Driver Station: reads the controllers (and the keyboard) 100 times a second on its own
 * thread, so a slow screen repaint never delays or drops input, and delivers each reading to the
 * robot's gamepad1/gamepad2 after the Driver Station-to-robot delay (the Wi-Fi link and the apps
 * on both ends; robot.jsonc hubTiming.gamepadDelayMs), timed in simulated time. Controller stick
 * values get the Driver Station's deadzone for that type of controller (see PhysicalGamepads).
 * Each robot has its own Driver Station, fed by its own controller slots; the keyboard drives
 * gamepad1 of whichever robot it's switched to.
 */
final class DriverStation {
    static final long PERIOD_MS = 10;

    private record Reading(long simNanos, Gamepad state) {}

    private final Simulation sim;
    private final OpModeRunner runner;
    private final KeyboardGamepad keyboard;
    private final BooleanSupplier keyboardHere;
    private final PhysicalGamepads controllers;
    private final int firstSlot;
    private final long delayNanos;
    private final Consumer<Integer> onSent;
    private final ArrayDeque<Reading>[] queues;
    private volatile boolean running = true;
    private final Thread thread;

    @SuppressWarnings("unchecked")
    DriverStation(Simulation sim, OpModeRunner runner, KeyboardGamepad keyboard, PhysicalGamepads controllers,
                  double delayMs, Consumer<Integer> onSent) {
        this(sim, runner, keyboard, () -> true, controllers, 0, delayMs, onSent);
    }

    /**
     * firstSlot: the controller slot of this robot's gamepad1 (0 for robot 1, 2 for robot 2);
     * keyboardHere: true while the keyboard drives this robot.
     */
    DriverStation(Simulation sim, OpModeRunner runner, KeyboardGamepad keyboard, BooleanSupplier keyboardHere,
                  PhysicalGamepads controllers, int firstSlot, double delayMs, Consumer<Integer> onSent) {
        this.sim = sim;
        this.runner = runner;
        this.keyboard = keyboard;
        this.keyboardHere = keyboardHere;
        this.controllers = controllers;
        this.firstSlot = firstSlot;
        this.delayNanos = Math.round(delayMs * 1e6);
        this.onSent = onSent;
        this.queues = new ArrayDeque[]{new ArrayDeque<Reading>(), new ArrayDeque<Reading>()};
        thread = new Thread(this::run, firstSlot == 0 ? "Driver Station" : "Driver Station (robot " + (firstSlot / 2 + 1) + ")");
        thread.setDaemon(true);
        thread.start();
    }

    void close() {
        running = false;
        thread.interrupt();
    }

    private void run() {
        long last = System.nanoTime();
        while (running) {
            long now = System.nanoTime();
            double dt = Math.min(0.1, (now - last) / 1e9);
            last = now;
            try {
                tick(dt);
            } catch (RuntimeException e) {
                // keep the Driver Station alive whatever one reading did
            }
            try {
                Thread.sleep(PERIOD_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void tick(double dt) {
        OpModeRunner.State st = runner.state();
        boolean live = st == OpModeRunner.State.INIT || st == OpModeRunner.State.RUNNING;
        Gamepad[] targets = {runner.gamepad1, runner.gamepad2};
        long simNow = sim.clock.nanos();
        for (int i = 0; i < 2; i++) {
            Gamepad next = i == 0 && keyboardHere.getAsBoolean() ? keyboard.state(dt) : new Gamepad();
            controllers.merge(firstSlot + i, next);
            // An AI agent driving this gamepad (the gamepad tool) replaces the controllers and keyboard.
            Gamepad agent = org.ftctestbed.viewer.agent.AgentGamepads.get(runner, i + 1);
            if (agent != null) {
                next = new Gamepad();
                next.copy(agent);
            }
            next.id = targets[i].id;
            ArrayDeque<Reading> q = queues[i];
            if (!live) {
                q.clear(); // a new OpMode starts with fresh gamepads
                continue;
            }
            q.addLast(new Reading(simNow, next));
            Reading due = null;
            while (!q.isEmpty() && q.peekFirst().simNanos() + delayNanos <= simNow) due = q.pollFirst();
            if (due != null) {
                targets[i].simApply(due.state());
                onSent.accept(i);
            }
            while (q.size() > 200) q.pollFirst(); // paused for long: don't pile up
        }
    }
}

package org.ftctestbed.viewer;

import java.util.ArrayList;
import java.util.List;

/**
 * Which connected controller feeds which gamepad, like the Driver Station's Start+A / Start+B.
 * Slots 0 and 1 are robot 1's gamepad1 and gamepad2; with a partner robot, 2 and 3 are robot 2's.
 * A new controller takes the first free slot in the order drivers most likely want: robot 1's
 * gamepad1, then (with a partner) robot 2's gamepad1, then the gamepad2s.
 *
 * Not thread-safe: PhysicalGamepads locks around it.
 */
final class ControllerSlots {
    static final int SLOTS = 4;

    private final int[] slot = {-1, -1, -1, -1}; // controller index in each slot, or -1
    private int robots = 1;

    int robots() {
        return robots;
    }

    /** The slots in use, in the order new controllers fill them. */
    private int[] order() {
        return robots > 1 ? new int[]{0, 2, 1, 3} : new int[]{0, 1};
    }

    /**
     * How many robots the controllers drive (1 or 2). The controllers already assigned are dealt
     * out again in the new order, so turning a partner on gives the second controller to robot 2.
     */
    void setRobots(int n) {
        n = Math.max(1, Math.min(2, n));
        if (n == robots) return;
        List<Integer> pads = new ArrayList<>();
        for (int s : order()) if (slot[s] >= 0) pads.add(slot[s]);
        java.util.Arrays.fill(slot, -1);
        robots = n;
        int[] next = order();
        for (int k = 0; k < pads.size() && k < next.length; k++) slot[next[k]] = pads.get(k);
    }

    /** A controller was connected: the slot it gets, or -1 if every slot is taken. */
    int connected(int pad) {
        for (int s : slot) if (s == pad) return -1;
        for (int s : order()) {
            if (slot[s] < 0) {
                slot[s] = pad;
                return s;
            }
        }
        return -1;
    }

    void disconnected(int pad) {
        for (int s = 0; s < SLOTS; s++) if (slot[s] == pad) slot[s] = -1;
    }

    /**
     * Start+A / B / X / Y: this controller takes that slot (leaving any other it had; a
     * controller already there is unassigned). False if nothing changed or the slot isn't in use.
     */
    boolean assign(int pad, int s) {
        if (s < 0 || s >= 2 * robots || slot[s] == pad) return false;
        disconnected(pad);
        slot[s] = pad;
        return true;
    }

    /** The controller in a slot, or -1. */
    int padIn(int s) {
        return s >= 0 && s < SLOTS ? slot[s] : -1;
    }

    boolean isAssigned(int pad) {
        for (int s : slot) if (s == pad) return true;
        return false;
    }

    /** "gamepad1", or "Robot 2 gamepad1" with a partner. */
    String name(int s) {
        String pad = "gamepad" + (s % 2 + 1);
        return robots > 1 ? "Robot " + (s / 2 + 1) + " " + pad : pad;
    }
}

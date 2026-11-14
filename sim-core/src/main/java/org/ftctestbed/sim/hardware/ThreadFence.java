package org.ftctestbed.sim.hardware;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ends OpMode threads that ignored STOP. Java can't kill a thread, so a fenced thread is ended
 * the next time it touches simulated hardware or calls sleep()/idle(), the way a Robot
 * Controller restart ends a stuck OpMode on a real robot. Until then it can't move anything.
 */
public final class ThreadFence {
    private static final Set<Thread> FENCED = ConcurrentHashMap.newKeySet();

    /** Thrown in a fenced thread; an Error so team code's catch (Exception e) doesn't swallow it. */
    public static final class OpModeKilled extends Error {
        OpModeKilled() {
            super("This OpMode ignored STOP and was ended (on a real robot the Robot Controller app would restart)");
        }
    }

    private ThreadFence() {}

    public static void fence(Thread t) {
        FENCED.add(t);
        FENCED.removeIf(x -> !x.isAlive() && x != t);
    }

    public static boolean isFenced(Thread t) {
        return !FENCED.isEmpty() && FENCED.contains(t);
    }

    private static final java.util.concurrent.atomic.LongAdder ACCESSES = new java.util.concurrent.atomic.LongAdder();

    /** How many hardware accesses there have been (lockstep uses it to tell polling from computing). */
    public static long accesses() {
        return ACCESSES.sum();
    }

    /** Called on every hardware access: ends the calling thread if it was fenced. */
    public static void check() {
        ACCESSES.increment();
        if (!FENCED.isEmpty() && FENCED.contains(Thread.currentThread())) throw new OpModeKilled();
    }
}

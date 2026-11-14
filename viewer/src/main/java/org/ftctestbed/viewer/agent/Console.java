package org.ftctestbed.viewer.agent;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * What robot code and the simulator print (System.out, System.err, RobotLog), kept for agents to
 * read with console_read: the newest 20,000 lines, each numbered, with the thread that printed it
 * (an OpMode's thread is named after it).
 */
public final class Console {
    public record Line(long seq, long wallMillis, String thread, String text) {}

    private static final int KEEP = 20_000;
    private static final Deque<Line> LINES = new ArrayDeque<>();
    private static long next = 1;

    private Console() {}

    /** Adds text (split into lines). */
    public static void add(String text) {
        String thread = Thread.currentThread().getName();
        long now = System.currentTimeMillis();
        synchronized (LINES) {
            for (String l : text.split("\n", -1)) {
                if (l.isEmpty()) continue;
                LINES.addLast(new Line(next++, now, thread, l));
                if (LINES.size() > KEEP) LINES.removeFirst();
            }
        }
    }

    /** Lines numbered after since (0: from the oldest kept), at most limit, optionally only those containing a string. */
    public static List<Line> since(long since, int limit, String contains) {
        List<Line> out = new ArrayList<>();
        synchronized (LINES) {
            for (Line l : LINES) {
                if (l.seq() <= since) continue;
                if (contains != null && !l.text().contains(contains)) continue;
                out.add(l);
            }
        }
        return out.size() > limit ? out.subList(out.size() - limit, out.size()) : out;
    }

    /** The number the next line will get (read the console "from now" with since = next() - 1). */
    public static long next() {
        synchronized (LINES) {
            return next;
        }
    }

    /**
     * Sends System.out and System.err here (and on to stderr, for a person watching the server),
     * so nothing robot code prints can get into the MCP channel on stdout.
     */
    public static void captureSystemStreams(PrintStream human) {
        PrintStream capture = new PrintStream(new LineStream(human), true, StandardCharsets.UTF_8);
        System.setOut(capture);
        System.setErr(capture);
    }

    private static final class LineStream extends OutputStream {
        private final PrintStream human;
        private final StringBuilder pending = new StringBuilder();

        LineStream(PrintStream human) {
            this.human = human;
        }

        @Override
        public synchronized void write(int b) {
            if (human != null) human.write(b);
            pending.append((char) b);
            if (b == '\n') flushLine();
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (human != null) human.write(b, off, len);
            pending.append(new String(b, off, len, StandardCharsets.UTF_8));
            if (pending.indexOf("\n") >= 0) flushLine();
        }

        private void flushLine() {
            add(pending.toString());
            pending.setLength(0);
        }
    }
}

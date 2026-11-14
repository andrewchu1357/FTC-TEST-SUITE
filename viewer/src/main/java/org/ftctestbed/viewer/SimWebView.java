package org.ftctestbed.viewer;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.ftctestbed.sim.SimRunner;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.Snapshot;
import org.ftctestbed.sim.config.ConfigNode;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.Jsonc;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.MatchController;
import org.ftctestbed.sim.game.RobotPieceHandling;
import org.ftctestbed.sim.game.SeasonSnapshot;
import org.ftctestbed.sim.opmode.OpModeInfo;
import org.ftctestbed.sim.opmode.OpModeRunner;
import org.ftctestbed.sim.season.biobuzz.BioBuzzSnapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.ftctestbed.sim.season.biobuzz.BioBuzzField.*;

/**
 * A view-only web page of the running simulator: the field, the score, the match clock and the
 * robot's stats, streamed live to any browser. It can't change anything - the server only
 * answers GET requests, and none of them start, stop, pause or edit anything.
 *
 * It starts when the simulator opens and stops with it. Who can open it is set in
 * {@code config/web-view.jsonc}: this computer only, this computer and your Tailscale tailnet
 * (the default), or the whole local network.
 */
final class SimWebView {
    static final int DEFAULT_PORT = 8766;
    private static final int MAX_STREAMS = 24;
    private static final long FRAME_MILLIS = 40; // 25 frames per second
    private static final int MAX_FLIGHT_POINTS = 80;
    /** Stats history kept for the charts, so a page opened mid-match shows the last 30 s at once. */
    private static final int HISTORY_SAMPLES = 300;
    private static final double HISTORY_INTERVAL = 0.1;
    private static final Map<String, String[]> ASSETS = Map.of(
            "/", new String[]{"web/index.html", "text/html; charset=utf-8"},
            "/index.html", new String[]{"web/index.html", "text/html; charset=utf-8"},
            "/view.css", new String[]{"web/view.css", "text/css; charset=utf-8"},
            "/view.js", new String[]{"web/view.js", "text/javascript; charset=utf-8"});

    /** Who may open the page. */
    enum Access { LOCAL, TAILSCALE, NETWORK }

    record Settings(boolean enabled, int port, Access access) {}

    /** The simulation being shown. Replaced when the window loads a different robot or field. */
    record Source(Simulation sim, SimRunner physics, OpModeRunner opModes, MatchController match) {}

    private final Settings settings;
    private volatile Source source;
    private final AtomicInteger version = new AtomicInteger();
    private final AtomicInteger streams = new AtomicInteger();
    private final Object frameLock = new Object();
    private String latestFrame;
    private long frameSeq;
    private volatile boolean running;
    private HttpServer server;
    private final java.util.ArrayDeque<double[]> history = new java.util.ArrayDeque<>(); // {t, speed in/s, volts, amps, loop ms}
    private java.util.function.Consumer<Simulation> sampler;
    private double lastSample = Double.NEGATIVE_INFINITY;

    SimWebView(Settings settings) {
        this.settings = settings;
    }

    static Settings loadSettings(Path projectDir) {
        Path file = projectDir.resolve("config/web-view.jsonc");
        if (!Files.exists(file)) return new Settings(true, DEFAULT_PORT, Access.TAILSCALE);
        try {
            ConfigNode n = ConfigNode.fromObject(file.getFileName().toString(), Jsonc.parse(Files.readString(file), file.toString()));
            String access = n.getString("access", "tailscale").toUpperCase(Locale.ROOT);
            return new Settings(n.getBoolean("enabled", true), n.getInt("port", DEFAULT_PORT), Access.valueOf(access));
        } catch (Exception e) {
            throw new IllegalArgumentException("config/web-view.jsonc: " + e.getMessage()
                    + " (access must be \"local\", \"tailscale\" or \"network\")");
        }
    }

    /** Point the page at a (new) simulation. Open pages switch over by themselves. */
    void show(Source s) {
        Source old = source;
        if (old != null && sampler != null) old.sim().removeStepListener(sampler);
        synchronized (history) {
            history.clear();
            lastSample = Double.NEGATIVE_INFINITY;
        }
        // Physics thread, every step: cheap reads only, a sample every 0.1 s of robot time.
        sampler = sim -> {
            double t = sim.clock.seconds();
            if (t - lastSample < HISTORY_INTERVAL - 1e-9) return;
            lastSample = t;
            double[] sample = {t, sim.robotSpeed() / IN, sim.batteryVolts(), sim.batteryCurrent(), s.physics().robotLoopMillis()};
            synchronized (history) {
                history.addLast(sample);
                while (history.size() > HISTORY_SAMPLES) history.removeFirst();
            }
        };
        s.sim().addStepListener(sampler);
        source = s;
        version.incrementAndGet();
    }

    /** Starts serving; returns the addresses to open in a browser. */
    List<String> start() throws IOException {
        InetSocketAddress bind = settings.access() == Access.LOCAL
                ? new InetSocketAddress(InetAddress.getLoopbackAddress(), settings.port())
                // All interfaces, so the page works even if Tailscale connects after the simulator
                // started; requests that didn't arrive over an allowed network are refused.
                : new InetSocketAddress(settings.port());
        server = HttpServer.create(bind, 32);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(MAX_STREAMS + 6, r -> {
            Thread t = new Thread(r, "web view");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        running = true;
        Thread producer = new Thread(this::produceFrames, "web view frames");
        producer.setDaemon(true);
        producer.start();

        int port = server.getAddress().getPort();
        List<String> urls = new ArrayList<>();
        urls.add("http://localhost:" + port + "/");
        if (settings.access() != Access.LOCAL) {
            String dns = tailscaleDnsName();
            if (dns != null) urls.add("http://" + dns + ":" + port + "/");
            for (String ip : addresses(true)) urls.add("http://" + ip + ":" + port + "/");
        }
        if (settings.access() == Access.NETWORK) for (String ip : addresses(false)) urls.add("http://" + ip + ":" + port + "/");
        return urls;
    }

    int port() {
        return server.getAddress().getPort();
    }

    void stop() {
        running = false;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
        if (server != null) server.stop(0);
    }

    // ------------------------------------------------------------------ access

    /** Tailscale gives each device an address in 100.64.0.0/10 and fd7a:115c:a1e0::/48. */
    static boolean isTailscale(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
        if (a instanceof Inet6Address) {
            return (b[0] & 0xFF) == 0xFD && (b[1] & 0xFF) == 0x7A && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0x5C
                    && (b[4] & 0xFF) == 0xA1 && (b[5] & 0xFF) == 0xE0;
        }
        return false;
    }

    /** Judged by the address the request arrived on, which a device on the local network can't fake. */
    private boolean allowed(HttpExchange ex) {
        InetAddress local = ex.getLocalAddress().getAddress();
        if (local.isLoopbackAddress()) return true;
        return switch (settings.access()) {
            case LOCAL -> false;
            case TAILSCALE -> isTailscale(local);
            case NETWORK -> true;
        };
    }

    /** IPv4 addresses of this computer: Tailscale ones, or local-network ones. */
    private static List<String> addresses(boolean tailscale) {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    if (tailscale ? isTailscale(a) : a.isSiteLocalAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    /** This computer's MagicDNS name (e.g. "my-mac.tailnet-name.ts.net"), if the Tailscale CLI answers. */
    private static String tailscaleDnsName() {
        // On the PATH, then where each system's installer puts it (Windows: Program Files).
        String programFiles = System.getenv("ProgramFiles") != null ? System.getenv("ProgramFiles") : "C:\\Program Files";
        for (String cli : List.of("tailscale", "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
                "/usr/local/bin/tailscale", "/opt/homebrew/bin/tailscale", programFiles + "\\Tailscale\\tailscale.exe")) {
            Path out = null;
            try {
                // Output goes to a file so a stuck CLI can't block startup: give up after 3 s.
                out = Files.createTempFile("tailscale-status", ".json");
                Process p = new ProcessBuilder(cli, "status", "--json")
                        .redirectOutput(out.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!p.waitFor(3, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    continue;
                }
                if (p.exitValue() != 0) continue;
                Object parsed = Jsonc.parse(Files.readString(out), "tailscale status");
                if (parsed instanceof Map<?, ?> root && root.get("Self") instanceof Map<?, ?> self
                        && self.get("DNSName") instanceof String dns && !dns.isBlank()) {
                    return dns.endsWith(".") ? dns.substring(0, dns.length() - 1) : dns;
                }
            } catch (Exception ignored) {
                // Not installed here, or not running: try the next place.
            } finally {
                if (out != null) {
                    try {
                        Files.deleteIfExists(out);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ requests

    private void handle(HttpExchange ex) throws IOException {
        try {
            var h = ex.getResponseHeaders();
            h.set("X-Content-Type-Options", "nosniff");
            h.set("Referrer-Policy", "no-referrer");
            h.set("X-Frame-Options", "DENY");
            h.set("Content-Security-Policy", "default-src 'self'; img-src 'self' data:; frame-ancestors 'none'");
            if (!allowed(ex)) {
                text(ex, 403, "This page is only available from this computer"
                        + (settings.access() == Access.TAILSCALE ? " and over Tailscale." : "."));
                return;
            }
            String method = ex.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                // View only: nothing here changes the simulation.
                h.set("Allow", "GET, HEAD");
                text(ex, 405, "This page is view-only.");
                return;
            }
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/api/stream" -> stream(ex);
                case "/api/frame" -> json(ex, frameJson());
                case "/api/field" -> json(ex, fieldJson());
                case "/api/history" -> json(ex, historyJson());
                default -> {
                    String[] asset = ASSETS.get(path);
                    if (asset == null) text(ex, 404, "Not found.");
                    else asset(ex, asset[0], asset[1]);
                }
            }
        } catch (IOException e) {
            // The browser went away.
        } catch (RuntimeException e) {
            try {
                text(ex, 500, "Something went wrong: " + e);
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    private static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : body.length);
        if (!head) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static void text(HttpExchange ex, int status, String message) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, "text/plain; charset=utf-8", message.getBytes(StandardCharsets.UTF_8));
    }

    private static void json(HttpExchange ex, String body) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, 200, "application/json; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void asset(HttpExchange ex, String resource, String type) throws IOException {
        byte[] body;
        try (InputStream in = SimWebView.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                text(ex, 404, "Not found.");
                return;
            }
            body = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        send(ex, 200, type, body);
    }

    /** Server-sent events: the latest frame, about 25 times a second, until the page closes. */
    private void stream(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("HEAD")) {
            ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
            ex.sendResponseHeaders(200, -1);
            return;
        }
        if (streams.incrementAndGet() > MAX_STREAMS) {
            streams.decrementAndGet();
            text(ex, 503, "Too many viewers right now; try again later.");
            return;
        }
        try {
            var h = ex.getResponseHeaders();
            h.set("Content-Type", "text/event-stream; charset=utf-8");
            h.set("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            out.write("retry: 2000\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            long seen = -1;
            while (running) {
                String frame;
                synchronized (frameLock) {
                    if (frameSeq == seen) frameLock.wait(2000);
                    frame = frameSeq != seen ? latestFrame : null;
                    seen = frameSeq;
                }
                byte[] msg = (frame != null ? "data: " + frame + "\n\n" : ": waiting for the simulator\n\n").getBytes(StandardCharsets.UTF_8);
                out.write(msg);
                out.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            streams.decrementAndGet();
        }
    }

    /** Builds one frame for all open streams at a time, only while someone is watching. */
    private void produceFrames() {
        while (running) {
            long start = System.nanoTime();
            if (streams.get() > 0 && source != null) {
                String frame;
                try {
                    frame = frameJson();
                } catch (RuntimeException e) {
                    frame = null; // e.g. the window is switching robots; try again next frame
                }
                if (frame != null) {
                    synchronized (frameLock) {
                        latestFrame = frame;
                        frameSeq++;
                        frameLock.notifyAll();
                    }
                }
            }
            long sleep = (streams.get() > 0 ? FRAME_MILLIS : 250) - (System.nanoTime() - start) / 1_000_000;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ data

    /** What doesn't change while a robot and field are loaded: field size, elements, robot size. */
    String fieldJson() {
        Source src = source;
        JsonWriter w = new JsonWriter().beginObject();
        w.field("v", version.get());
        if (src == null) return w.field("ready", false).endObject().toString();
        FieldConfig f = src.sim().fieldConfig;
        RobotConfig r = src.sim().robotConfig;
        w.field("ready", true).field("name", f.name()).field("robot", r.source())
                .field("size", f.size()).field("tile", f.tile()).field("season", f.season());
        w.name("robotSize").beginObject().field("length", r.length()).field("width", r.width())
                .field("height", r.height()).field("wheelRadius", r.drivetrain().wheelRadius()).endObject();
        w.name("obstacles").beginArray();
        for (FieldConfig.Shape o : f.obstacles()) {
            w.beginObject().field("name", o.name()).field("shape", o.shape()).field("x", o.x()).field("y", o.y())
                    .field("heading", o.heading()).field("width", o.width()).field("height", o.height())
                    .field("diameter", o.diameter()).color("color", o.color());
            w.name("points").beginArray();
            if (o.points() != null) for (double[] p : o.points()) w.beginArray().value(p[0]).value(p[1]).endArray();
            w.endArray().endObject();
        }
        w.endArray();
        if ("biobuzz".equals(f.season())) {
            w.name("biobuzz").beginObject()
                    .field("half", HALF).field("in", IN)
                    .field("frameHalfWidth", FRAME_HALF_WIDTH).field("frameHalfDepth", FRAME_HALF_DEPTH)
                    .field("frameFoot", FRAME_FOOT_THICKNESS).field("mouthHalfWidth", MOUTH_HALF_WIDTH)
                    .field("legFootX", LEG_FOOT[0]).field("legFootY", LEG_FOOT[1])
                    .field("legTopX", LEG_TOP[0]).field("legTopY", LEG_TOP[1])
                    .field("nectarDiameter", NECTAR_DIAMETER).field("flowerOpeningRadius", FLOWER_OPENING_RADIUS);
            rect(w, "redLoadingZone", RED_LOADING_ZONE);
            rect(w, "blueLoadingZone", BLUE_LOADING_ZONE);
            rect(w, "redGarden", RED_GARDEN);
            rect(w, "blueGarden", BLUE_GARDEN);
            w.name("tagClusters").beginArray();
            for (int[] t : TAG_CLUSTERS) w.beginArray().value(t[0]).value(t[1]).value(t[2]).endArray();
            w.endArray().endObject();
        }
        return w.endObject().toString();
    }

    /** The last 30 s of robot stats, oldest first: [t, speed in/s, battery V, current A, loop ms]. */
    String historyJson() {
        List<double[]> samples;
        synchronized (history) {
            samples = new ArrayList<>(history);
        }
        JsonWriter w = new JsonWriter().beginObject().field("v", version.get()).name("samples").beginArray();
        for (double[] s : samples) {
            w.beginArray();
            for (double d : s) w.value(d);
            w.endArray();
        }
        return w.endArray().endObject().toString();
    }

    private static void rect(JsonWriter w, String name, double[] r) {
        w.name(name).beginArray().value(r[0]).value(r[1]).value(r[2]).value(r[3]).endArray();
    }

    /** Everything that moves, for one moment. Only reads the simulation (snapshot and getters). */
    String frameJson() {
        Source src = source;
        JsonWriter w = new JsonWriter().beginObject();
        w.field("v", version.get());
        if (src == null) return w.field("ready", false).endObject().toString();
        Simulation sim = src.sim();
        Snapshot s = sim.snapshot();
        w.field("ready", true).field("t", s.time());

        w.name("robot").beginObject().field("x", s.x()).field("y", s.y()).field("h", s.heading())
                .field("vx", s.vx()).field("vy", s.vy()).field("w", s.omega())
                .field("battery", s.batteryVolts()).field("current", sim.batteryCurrent())
                .color("led", s.hubLedColor() & 0xFFFFFF).field("alliance", s.alliance())
                .field("charge", s.physics().batteryCharge()).field("usedMah", s.physics().batteryUsedMah())
                .field("restingVolts", s.physics().batteryRestingVolts()).field("cgHeight", s.physics().cgHeight())
                .field("ax", s.physics().accelX()).field("ay", s.physics().accelY())
                .field("lifting", s.physics().liftingWheels()).field("mass", sim.robotConfig.mass()).endObject();

        w.name("wheels").beginArray();
        for (Snapshot.Wheel wh : s.wheels()) {
            w.beginObject().field("label", wh.label()).field("x", wh.x()).field("y", wh.y())
                    .field("duty", wh.duty()).field("slip", wh.slipping()).field("load", wh.load())
                    .field("lifting", wh.lifting()).endObject();
        }
        w.endArray();

        w.name("pieces").beginArray();
        for (Snapshot.Piece p : s.pieces()) {
            if (!p.state().equals("FLOOR") && !p.state().equals("AIRBORNE")) continue;
            w.beginObject().field("id", p.id()).field("ball", p.ball()).field("r", p.radius())
                    .field("w", p.width()).field("l", p.height()).color("c", p.color())
                    .field("x", p.x()).field("y", p.y()).field("z", p.z()).field("h", p.heading())
                    .field("air", p.state().equals("AIRBORNE")).endObject();
        }
        w.endArray();

        w.name("rays").beginArray();
        for (Snapshot.Ray r : s.rays()) {
            w.beginObject().field("name", r.name()).field("x0", r.x0()).field("y0", r.y0())
                    .field("x1", r.x1()).field("y1", r.y1()).field("hit", r.hit()).endObject();
        }
        w.endArray();

        RobotPieceHandling.Parts parts = s.parts();
        if (parts != null) {
            w.name("parts").beginObject();
            if (parts.intakeZone() != null) {
                double[] z = parts.intakeZone();
                w.name("intakeZone").beginArray().value(z[0]).value(z[1]).value(z[2]).value(z[3]).endArray();
            }
            w.field("intakeDirection", parts.intakeDirection()).field("staged", parts.staged())
                    .field("flywheelRpm", parts.flywheelRpm());
            w.name("hopper").beginArray();
            for (int c : parts.hopperColors()) w.value(String.format("#%06x", c & 0xFFFFFF));
            w.endArray();
            if (parts.launcherPoint() != null) {
                w.name("launcher").beginArray().value(parts.launcherPoint()[0]).value(parts.launcherPoint()[1]).endArray();
            }
            w.name("claws").beginArray();
            for (RobotPieceHandling.ClawState c : parts.claws()) {
                w.beginObject().field("name", c.name()).field("x", c.x()).field("y", c.y()).field("height", c.height())
                        .field("closed", c.closed()).color("held", c.heldColor()).endObject();
            }
            w.endArray().endObject();
        }

        w.name("flights").beginArray();
        for (Snapshot.Flight fl : s.flights()) {
            w.beginObject().color("c", fl.color()).field("outcome", fl.outcome());
            w.name("points").beginArray();
            List<double[]> pts = fl.points();
            int step = Math.max(1, (pts.size() + MAX_FLIGHT_POINTS - 1) / MAX_FLIGHT_POINTS);
            for (int i = 0; i < pts.size(); i += step) point(w, pts.get(i));
            if (!pts.isEmpty() && (pts.size() - 1) % step != 0) point(w, pts.get(pts.size() - 1));
            w.endArray().endObject();
        }
        w.endArray();

        writeSeason(w, s.season());

        MatchController match = src.match();
        w.name("match").beginObject().field("phase", s.matchPhase()).field("clock", s.matchClock())
                .field("running", match != null && match.running()).endObject();

        OpModeRunner r = src.opModes();
        OpModeInfo cur = r.current();
        w.name("opmode").beginObject()
                .field("name", cur != null ? cur.name() : null)
                .field("autonomous", cur != null && cur.autonomous())
                .field("state", r.state().name()).field("elapsed", r.elapsed())
                .field("autoTimer", r.autoTimer())
                .field("error", r.error()).field("warning", r.warning());
        w.name("telemetry").beginArray();
        for (String line : r.telemetry().publishedLines()) w.value(line);
        var packet = com.acmerobotics.dashboard.FtcDashboard.getInstance().simLastPacket();
        if (packet != null && (!packet.simData().isEmpty() || !packet.simLines().isEmpty())) {
            w.value("── FTC Dashboard ──");
            packet.simData().forEach((k, v) -> w.value(k + " : " + v));
            for (String l : packet.simLines()) w.value(l);
        }
        List<String> panels = com.bylazar.telemetry.PanelsTelemetry.INSTANCE.simLines();
        if (!panels.isEmpty()) {
            w.value("── Panels ──");
            for (String l : panels) w.value(l);
        }
        if (s.odometry() != null && !s.odometry().isEmpty()) {
            w.value("── Odometry (what the sensors report) ──");
            for (Snapshot.Odometry o : s.odometry()) {
                w.value(String.format(java.util.Locale.US, "%s (%s): x %.2f in, y %.2f in, heading %.1f°, %s", o.name(), o.kind(),
                        o.x() / 0.0254, o.y() / 0.0254,
                        Math.toDegrees(org.firstinspires.ftc.robotcore.external.navigation.AngleUnit.normalizeRadians(o.heading())), o.status()));
            }
        }
        w.endArray().endObject();

        SimRunner physics = src.physics();
        w.name("sim").beginObject().field("paused", physics.isPaused()).field("speed", physics.speed())
                .field("loopMs", physics.robotLoopMillis()).endObject();

        w.name("motors").beginArray();
        for (Snapshot.Motor m : s.motors()) {
            w.beginObject().field("name", m.name()).field("hub", m.hub()).field("port", m.port()).field("mode", m.mode())
                    .field("power", m.power()).field("position", m.position()).field("velocity", m.velocityTps())
                    .field("current", m.currentA()).field("rpm", m.velocityTps() * 60 / m.ticksPerRev()).endObject();
        }
        w.endArray();

        w.name("servos").beginArray();
        for (Snapshot.Servo sv : s.servos()) {
            w.beginObject().field("name", sv.name()).field("continuous", sv.continuous()).field("commanded", sv.commanded())
                    .field("angleDeg", Math.toDegrees(sv.angle())).field("enabled", sv.enabled()).endObject();
        }
        w.endArray();

        w.name("mechanisms").beginArray();
        for (Snapshot.Mechanism m : s.mechanisms()) {
            w.beginObject().field("name", m.name()).field("type", m.type()).field("position", m.position())
                    .field("min", m.min()).field("max", m.max()).endObject();
        }
        w.endArray();
        return w.endObject().toString();
    }

    private static void point(JsonWriter w, double[] p) {
        w.beginArray().value(p[0]).value(p[1]).value(p[2]).endArray();
    }

    private static void writeSeason(JsonWriter w, SeasonSnapshot season) {
        if (season == null) return;
        w.name("season").beginObject().field("title", season.title());
        for (String a : new String[]{"red", "blue"}) {
            w.name(a).beginObject().field("score", season.score(a));
            w.name("breakdown").beginArray();
            for (String l : season.breakdown(a)) w.value(l);
            w.endArray();
            w.name("rankingPoints").beginArray();
            for (String l : season.rankingPoints(a)) w.value(l);
            w.endArray().endObject();
        }
        w.name("notes").beginArray();
        for (String n : season.notes()) w.value(n);
        w.endArray();
        if (season instanceof BioBuzzSnapshot bb) {
            w.name("biobuzz").beginObject().field("flowersUnlocked", bb.flowersUnlocked());
            w.name("hives").beginArray();
            for (BioBuzzSnapshot.HiveState h : bb.hives()) {
                w.beginObject().field("alliance", h.alliance()).field("x", h.x()).field("tilt", h.tiltDegrees())
                        .field("tipping", h.tipping()).field("tips", h.tipsAuto() + h.tipsTeleop()).field("fill", h.fill());
                w.name("contents").beginArray();
                for (int c : h.contents()) w.value(String.format("#%06x", c & 0xFFFFFF));
                w.endArray().endObject();
            }
            w.endArray();
            w.name("flowers").beginArray();
            for (BioBuzzSnapshot.FlowerState f : bb.flowers()) {
                w.beginObject().field("axisX", f.axisX()).field("axisY", f.axisY()).field("nx", f.nx()).field("ny", f.ny())
                        .field("owner", f.owner()).field("scored", f.scored());
                w.name("stack").beginArray();
                for (int c : f.stack()) w.value(String.format("#%06x", c & 0xFFFFFF));
                w.endArray().endObject();
            }
            w.endArray();
            w.name("humanPlayer").beginObject();
            for (String a : new String[]{"red", "blue"}) {
                w.name(a).beginObject().field("nectar", bb.humanPlayerNectar().getOrDefault(a, 0))
                        .field("ready", bb.humanPlayerAllowed().getOrDefault(a, 0)).endObject();
            }
            w.endObject().endObject();
        }
        w.endObject();
    }
}

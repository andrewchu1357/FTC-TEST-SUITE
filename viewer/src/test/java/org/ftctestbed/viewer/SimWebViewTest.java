package org.ftctestbed.viewer;

import org.ftctestbed.sim.SimRunner;
import org.ftctestbed.sim.Simulation;
import org.ftctestbed.sim.config.FieldConfig;
import org.ftctestbed.sim.config.Jsonc;
import org.ftctestbed.sim.config.RobotConfig;
import org.ftctestbed.sim.game.MatchController;
import org.ftctestbed.sim.opmode.OpModeRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SimWebViewTest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private Simulation sim;
    private SimRunner physics;
    private SimWebView view;

    @BeforeEach
    void setUp() {
        Path dir = Path.of("").toAbsolutePath();
        sim = new Simulation(RobotConfig.load(dir.resolve("config/robot.jsonc")), FieldConfig.load(dir.resolve("config/field.jsonc")));
        physics = new SimRunner(sim);
        OpModeRunner runner = new OpModeRunner(sim, physics);
        physics.start();
        view = start(SimWebView.Access.LOCAL);
        view.show(new SimWebView.Source(sim, physics, runner, new MatchController(sim, runner)));
    }

    private SimWebView start(SimWebView.Access access) {
        SimWebView v = new SimWebView(new SimWebView.Settings(true, 0, access));
        try {
            v.start();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return v;
    }

    @AfterEach
    void tearDown() {
        view.stop();
        physics.shutdown();
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        return request(method, "localhost", view.port(), path);
    }

    private HttpResponse<String> request(String method, String host, int port, String path) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path)).timeout(Duration.ofSeconds(5));
        b.method(method, method.equals("GET") || method.equals("HEAD") ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString("{}"));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> json(String text) {
        return (Map<String, Object>) Jsonc.parse(text, "response");
    }

    @Test
    void servesThePageAndItsFiles() throws Exception {
        HttpResponse<String> page = request("GET", "/");
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("FTC Test Suite"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'self'"));
        assertEquals(200, request("GET", "/view.js").statusCode());
        assertEquals(200, request("GET", "/view.css").statusCode());
        assertEquals(404, request("GET", "/../config/robot.jsonc").statusCode());
        assertEquals(404, request("GET", "/web/view.js").statusCode());
    }

    @Test
    void fieldAndFrameAreValidJson() throws Exception {
        Map<String, Object> field = json(request("GET", "/api/field").body());
        assertEquals(true, field.get("ready"));
        assertEquals("biobuzz", field.get("season"));
        assertNotNull(field.get("biobuzz"));

        Map<String, Object> frame = json(request("GET", "/api/frame").body());
        assertEquals(true, frame.get("ready"));
        Map<String, Object> robot = (Map<String, Object>) frame.get("robot");
        assertNotNull(robot.get("battery"));
        assertFalse(((List<?>) frame.get("motors")).isEmpty());
        Map<String, Object> season = (Map<String, Object>) frame.get("season");
        assertNotNull(((Map<String, Object>) season.get("red")).get("score"));
        assertFalse(((List<?>) frame.get("pieces")).isEmpty());
    }

    @Test
    void keepsRecentHistoryForNewViewers() throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        List<?> samples = List.of();
        while (samples.size() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(100);
            samples = (List<?>) json(request("GET", "/api/history").body()).get("samples");
        }
        assertTrue(samples.size() >= 3, "history samples: " + samples.size());
        assertEquals(5, ((List<?>) samples.get(0)).size());
    }

    @Test
    void nothingCanBeChanged() throws Exception {
        for (String method : List.of("POST", "PUT", "DELETE", "PATCH")) {
            for (String path : List.of("/", "/api/frame", "/api/field", "/api/history", "/api/stream", "/api/start")) {
                HttpResponse<String> r = request(method, path);
                assertEquals(405, r.statusCode(), method + " " + path);
                assertEquals("GET, HEAD", r.headers().firstValue("Allow").orElse(""));
            }
        }
        assertEquals(404, request("GET", "/api/start").statusCode());
    }

    @Test
    void streamsFrames() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + view.port() + "/api/stream")).build();
        HttpResponse<java.io.InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        try (BufferedReader in = new BufferedReader(new InputStreamReader(r.body(), StandardCharsets.UTF_8))) {
            String line;
            int frames = 0;
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (frames < 3 && System.nanoTime() < deadline && (line = in.readLine()) != null) {
                if (!line.startsWith("data: ")) continue;
                assertEquals(true, json(line.substring(6)).get("ready"));
                frames++;
            }
            assertEquals(3, frames, "frames received");
        }
    }

    @Test
    void recognizesTailscaleAddresses() throws Exception {
        assertTrue(SimWebView.isTailscale(InetAddress.getByName("100.77.243.34")));
        assertTrue(SimWebView.isTailscale(InetAddress.getByName("100.64.0.1")));
        assertTrue(SimWebView.isTailscale(InetAddress.getByName("fd7a:115c:a1e0::7b39:f323")));
        assertFalse(SimWebView.isTailscale(InetAddress.getByName("100.128.0.1")));
        assertFalse(SimWebView.isTailscale(InetAddress.getByName("192.168.1.20")));
        assertFalse(SimWebView.isTailscale(InetAddress.getByName("10.0.0.5")));
    }

    @Test
    void tailscaleModeRefusesTheLocalNetwork() throws Exception {
        String lan = null;
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                if (a instanceof Inet4Address && a.isSiteLocalAddress()) lan = a.getHostAddress();
            }
        }
        assumeTrue(lan != null, "this computer has no local-network address");
        SimWebView ts = start(SimWebView.Access.TAILSCALE);
        try {
            assertEquals(403, request("GET", lan, ts.port(), "/").statusCode());
            assertEquals(200, request("GET", "localhost", ts.port(), "/").statusCode());
        } finally {
            ts.stop();
        }
    }
}

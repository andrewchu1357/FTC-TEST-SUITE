package org.ftctestbed.viewer.agent;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The agent API as an agent uses it: MCP messages in, tool results out, on a headless simulation. */
class AgentApiTest {
    private static final String STICK_DRIVE = """
            package org.firstinspires.ftc.teamcode.agenttest;
            import com.qualcomm.robotcore.eventloop.opmode.*;
            import com.qualcomm.robotcore.hardware.*;
            @TeleOp(name = "Agent Stick Drive", group = "Agent test")
            public class StickDrive extends LinearOpMode {
                @Override public void runOpMode() {
                    DcMotor[] m = {hardwareMap.get(DcMotor.class, "leftFront"), hardwareMap.get(DcMotor.class, "rightFront"),
                            hardwareMap.get(DcMotor.class, "leftBack"), hardwareMap.get(DcMotor.class, "rightBack")};
                    m[0].setDirection(DcMotor.Direction.REVERSE);
                    m[2].setDirection(DcMotor.Direction.REVERSE);
                    waitForStart();
                    while (opModeIsActive()) {
                        for (DcMotor d : m) d.setPower(-gamepad1.left_stick_y);
                        telemetry.addData("stick", gamepad1.left_stick_y);
                        telemetry.update();
                    }
                }
            }
            """;
    private static final String CRASH = """
            package org.firstinspires.ftc.teamcode.agenttest;
            import com.qualcomm.robotcore.eventloop.opmode.*;
            @Autonomous(name = "Agent Crash", group = "Agent test")
            public class Crash extends LinearOpMode {
                @Override public void runOpMode() {
                    waitForStart();
                    sleep(200);
                    Object o = null;
                    o.toString();
                }
            }
            """;

    private static AgentApi api;

    @BeforeAll
    static void start() {
        api = new AgentApi(Path.of("").toAbsolutePath(), "test");
    }

    @AfterAll
    static void stop() {
        api.close();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ok(String tool, Object... args) {
        AgentApi.Outcome o = api.call(tool, Json.obj(args));
        assertNull(o.error(), () -> tool + ": " + o.error());
        return o.result().data();
    }

    private static String error(String tool, Object... args) {
        AgentApi.Outcome o = api.call(tool, Json.obj(args));
        assertNotNull(o.error(), tool + " should have failed");
        return o.error();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rpc(McpServer s, int id, String method, Object params) {
        String reply = s.handle(Json.write(Json.obj("jsonrpc", "2.0", "id", id, "method", method, "params", params)));
        Map<String, Object> m = (Map<String, Object>) Json.parse(reply);
        assertNull(m.get("error"), () -> method + ": " + m.get("error"));
        return (Map<String, Object>) m.get("result");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mcpHandshakeToolsResourcesAndPrompts() {
        McpServer s = new McpServer(api);
        Map<String, Object> init = rpc(s, 1, "initialize", Json.obj("protocolVersion", "2025-03-26", "capabilities", Json.obj(),
                "clientInfo", Json.obj("name", "test", "version", "1")));
        assertEquals("2025-03-26", init.get("protocolVersion"));
        assertTrue(((String) init.get("instructions")).contains("sim_create"));
        assertNull(s.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));

        List<Map<String, Object>> tools = (List<Map<String, Object>>) rpc(s, 2, "tools/list", Json.obj()).get("tools");
        List<String> names = tools.stream().map(t -> (String) t.get("name")).toList();
        for (String n : List.of("project_info", "sim_create", "sim_step", "sim_state", "sim_record", "sim_image", "opmode_run",
                "opmode_control", "gamepad", "hardware", "robot_move", "game_pieces", "match", "compile_code", "write_file", "java_eval", "ui")) {
            assertTrue(names.contains(n), n);
        }
        for (Map<String, Object> t : tools) {
            assertEquals("object", ((Map<String, Object>) t.get("inputSchema")).get("type"), (String) t.get("name"));
            assertTrue(((String) t.get("description")).length() > 40, (String) t.get("name"));
        }

        Map<String, Object> read = rpc(s, 3, "resources/read", Json.obj("uri", "ftc-sim://docs/tools"));
        String md = (String) ((List<Map<String, Object>>) read.get("contents")).get(0).get("text");
        assertTrue(md.contains("### opmode_run"));
        Map<String, Object> prompt = rpc(s, 4, "prompts/get", Json.obj("name", "test_opmode", "arguments", Json.obj("opmode", "X")));
        assertTrue(Json.write(prompt).contains("opmode_run"));

        // Unknown methods and bad JSON get JSON-RPC errors; a tool's own failure is a result with isError.
        assertTrue(s.handle("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"nope\"}").contains("-32601"));
        assertTrue(s.handle("{not json").contains("-32700"));
        Map<String, Object> bad = rpc(s, 6, "tools/call", Json.obj("name", "read_file", "arguments", Json.obj("path", "../outside.txt")));
        assertEquals(true, bad.get("isError"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void agentCodeRunsInAHeadlessSessionDrivenByAGamepadScript() {
        Map<String, Object> s = ok("sim_create", "robot", "config/examples/starter-robot.jsonc", "name", "drive",
                "sources", List.of(Json.obj("path", "org/firstinspires/ftc/teamcode/agenttest/StickDrive.java", "content", STICK_DRIVE),
                        Json.obj("path", "org/firstinspires/ftc/teamcode/agenttest/Crash.java", "content", CRASH)));
        assertEquals("drive", s.get("session"));
        List<Map<String, Object>> robots = (List<Map<String, Object>>) s.get("robots");
        assertTrue(Json.write(robots.get(0).get("opModes")).contains("Agent Stick Drive"));

        // Stick forward for a second, then let go: the robot drives forward (+x from the red wall) and stops.
        Map<String, Object> run = ok("opmode_run", "session", "drive", "opmode", "Agent Stick Drive", "seconds", 2.0,
                "gamepad", List.of(Json.obj("t", 0, "gamepad1", Json.obj("left_stick_y", -0.6)), Json.obj("t", 1.0, "neutral", true)),
                "record", List.of("x", "motor.leftFront.power"), "image", true);
        assertEquals("time limit (2.0 s): STOP pressed", run.get("endedBy"));
        double x0 = ((Number) ((Map<String, Object>) run.get("start")).get("x")).doubleValue();
        double x1 = ((Number) ((Map<String, Object>) run.get("end")).get("x")).doubleValue();
        assertTrue(x1 - x0 > 10, "drove " + (x1 - x0) + " in");
        Map<String, Object> rec = (Map<String, Object>) run.get("recording");
        Map<String, Object> power = (Map<String, Object>) ((Map<String, Object>) rec.get("summary")).get("motor.leftFront.power");
        assertEquals(0.6, ((Number) power.get("max")).doubleValue(), 1e-6);

        // A crash comes back with the line of robot code.
        Map<String, Object> crash = ok("opmode_run", "session", "drive", "opmode", "Agent Crash", "reset", true);
        assertEquals("crashed", crash.get("endedBy"));
        assertTrue(String.valueOf(crash.get("error")).contains("Crash.java"), String.valueOf(crash.get("error")));

        // Step by step, state, hardware, and Java inside the simulator.
        ok("opmode_control", "session", "drive", "action", "init_start", "opmode", "Agent Stick Drive");
        ok("gamepad", "session", "drive", "state", Json.obj("left_stick_y", -1));
        Map<String, Object> step = ok("sim_step", "session", "drive", "seconds", 0.5);
        assertEquals(0.5, ((Number) step.get("ranSeconds")).doubleValue(), 1e-6);
        Map<String, Object> state = ok("sim_state", "session", "drive", "include", List.of("pose", "motors", "opmode"));
        assertTrue(Json.write(state).contains("\"stick : -1.0\"") || Json.write(state).contains("stick : -1"), Json.write(state));
        ok("opmode_control", "session", "drive", "action", "stop");
        Map<String, Object> hw = ok("hardware", "session", "drive", "action", "read", "name", "leftFront");
        assertTrue(Json.write(hw).contains("getCurrentPosition"));
        Map<String, Object> eval = ok("java_eval", "session", "drive", "code", "advance(0.25); return sim.clock.seconds() > 0 ? robot.number : -1;");
        assertEquals(1, ((Number) eval.get("returned")).intValue());

        byte[] png = api.call("sim_image", Json.obj("session", "drive")).result().pngs().get(0);
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        ok("sim_close", "session", "drive");
    }

    @Test
    @SuppressWarnings("unchecked")
    void compileErrorsNameTheGivenFileAndLine() {
        Map<String, Object> c = ok("compile_code", "robot", "config/examples/starter-robot.jsonc",
                "sources", List.of(Json.obj("path", "org/firstinspires/ftc/teamcode/agenttest/Broken.java",
                        "content", "package org.firstinspires.ftc.teamcode.agenttest;\nclass Broken { int x = \"no\"; }\n")));
        List<Map<String, Object>> problems = (List<Map<String, Object>>) c.get("problems");
        assertFalse(problems.isEmpty());
        assertEquals("sources: org/firstinspires/ftc/teamcode/agenttest/Broken.java", problems.get(0).get("file"));
        assertEquals(2, ((Number) problems.get(0).get("line")).intValue());
    }

    @Test
    void filesStayInsideTheProjectAndSomeAreOffLimits() {
        assertTrue(error("read_file", "path", "../../etc/hosts").contains("outside the project"));
        assertTrue(error("read_file", "path", "config/agent-api.jsonc").contains("isn't available"));
        assertTrue(error("write_file", "path", "robots/AnyTeam/repo/Foo.java", "content", "x").contains("read-only"));
        assertTrue(error("opmode_run", "session", "nope", "opmode", "x").contains("No session"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpNeedsTheTokenAndRefusesWebPages() throws Exception {
        String token = "test-token-0123456789abcdef";
        HttpTransport t = new HttpTransport(api, token);
        int port = t.start(0);
        try {
            HttpClient c = HttpClient.newHttpClient();
            String init = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}";
            URI mcp = URI.create("http://127.0.0.1:" + port + "/mcp");
            HttpResponse<String> noToken = c.send(HttpRequest.newBuilder(mcp).POST(HttpRequest.BodyPublishers.ofString(init)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, noToken.statusCode());
            HttpResponse<String> page = c.send(HttpRequest.newBuilder(mcp).header("Authorization", "Bearer " + token)
                    .header("Origin", "https://example.com").POST(HttpRequest.BodyPublishers.ofString(init)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, page.statusCode());
            HttpResponse<String> good = c.send(HttpRequest.newBuilder(mcp).header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(init)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, good.statusCode());
            assertTrue(good.body().contains("\"protocolVersion\":\"2025-06-18\""));
            HttpResponse<String> note = c.send(HttpRequest.newBuilder(mcp).header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, note.statusCode());
            HttpResponse<String> rest = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/tools/project_info"))
                    .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, rest.statusCode());
            Map<String, Object> body = (Map<String, Object>) Json.parse(rest.body());
            assertEquals(true, body.get("ok"));
        } finally {
            t.stop();
        }
    }
}

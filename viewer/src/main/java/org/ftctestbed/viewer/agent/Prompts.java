package org.ftctestbed.viewer.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Ready-made plans an agent's user can pick (MCP prompts): each is a first message that knows the tools. */
final class Prompts {
    private record Arg(String name, String description, boolean required) {}

    private record Prompt(String name, String title, String description, List<Arg> args, String template) {}

    private static final List<Prompt> PROMPTS = List.of(
            new Prompt("test_opmode", "Test an OpMode",
                    "Run one OpMode in the simulator, check what it did, and explain any problem with the line of code behind it.",
                    List.of(new Arg("opmode", "The OpMode's name as on the Driver Station.", true),
                            new Arg("expect", "What it should do (optional), e.g. \"drive to the basket and score 2 samples\".", false)),
                    """
                    Test the OpMode "{opmode}" in the FTC simulator (ftc-sim tools).
                    1. project_info, then sim_create with the default robot (or the right robot package).
                    2. opmode_run {"opmode": "{opmode}", "record": ["x","y","heading","speed","motor.*.power","mechanism.*"], "image": true}.
                    3. Read endedBy, error (crashes name the line of robot code), telemetry, telemetryHistory, console, the path in the picture and the recording.
                    4. Expected behavior: {expect}
                    5. If something is wrong, find the cause in the code (read_file / search_code), explain it, propose a fix, and test the fix by \
                    passing the changed files to a new sim_create as "sources" before suggesting it. Report what you ran and what you saw.
                    """),
            new Prompt("check_team_code", "Check all team code",
                    "Compile the team code, report files that don't compile or hardware names the config lacks, and smoke-test every OpMode.",
                    List.of(new Arg("robot", "Robot config (default config/robot.jsonc).", false)),
                    """
                    Check the team code for robot {robot} in the FTC simulator.
                    1. compile_code {"robot": "{robot}"}: list errors and files left out, with file and line, and configCheck problems.
                    2. sim_create {"robot": "{robot}"}, then for each OpMode: opmode_run with seconds 5 (AUTOs: 30) and reset true. Note crashes \
                    (error), warnings, OpModes that never stop, and ones that don't move the robot when they should.
                    3. Summarize per OpMode: OK / problem, with evidence and the line of code for each problem.
                    """),
            new Prompt("tune_mechanism", "Tune a mechanism",
                    "Measure a mechanism (lift, arm, flywheel, drive) in the simulator and tune its constants against a goal.",
                    List.of(new Arg("mechanism", "Which mechanism or subsystem, e.g. \"lift\" or \"the drive's heading PID\".", true),
                            new Arg("goal", "What good looks like, e.g. \"reach 25 in in under 1 s without overshoot\".", false)),
                    """
                    Tune {mechanism} in the FTC simulator. Goal: {goal}
                    1. Find the code and constants (search_code, read_file) and the mechanism in the robot config (validate_robot_config).
                    2. Write a small test OpMode that commands the move, as a "sources" file for sim_create (don't edit project files yet).
                    3. Run it with opmode_run, recording the mechanism's position, motor power, velocity and current (sim_record channels).
                    4. Change the constants (again via sources), rerun, and compare the recordings: rise time, overshoot, settling, steady error.
                    5. Report the numbers before and after and the constants you recommend; write them to the project only if asked.
                    """),
            new Prompt("add_mechanism", "Prototype a new mechanism",
                    "Add a new mechanism to a copy of the robot (config + subsystem code) and test it before anything is built.",
                    List.of(new Arg("description", "The mechanism, e.g. \"a 2-stage slide on a goBILDA 312 rpm motor lifting a 0.5 kg claw\".", true)),
                    """
                    Prototype this mechanism in the FTC simulator: {description}
                    1. Read config/robot.jsonc and the README's robot config section (docs topic readme); pick real parts with parts_catalog.
                    2. Write the new robot config text (the current one plus the mechanism) and check it with validate_robot_config {"text": ...}.
                    3. Write the subsystem class and a test OpMode as "sources".
                    4. sim_create with robot_config (the text) and sources; opmode_run the test with a recording of the mechanism and its motors.
                    5. Iterate until it works; report its speed, current draw, and anything that limits it, with the files ready to add.
                    """),
            new Prompt("play_match", "Play a match",
                    "Play a full match with the team's AUTO and TELEOP and break down the score.",
                    List.of(new Arg("auto", "AUTO OpMode name.", true), new Arg("teleop", "TELEOP OpMode name (optional).", false)),
                    """
                    Play a full FTC match in the simulator with AUTO "{auto}" and TELEOP "{teleop}".
                    sim_create, then match {"auto": "{auto}", "teleop": "{teleop}", "record": ["x","y","score.red","score.blue","phase"], "image": true}.
                    Report the score breakdown, ranking points, referee notes and any OpMode errors, and where points were lost.
                    """));

    private Prompts() {}

    static Map<String, Object> list() {
        List<Object> out = new ArrayList<>();
        for (Prompt p : PROMPTS) {
            List<Object> args = new ArrayList<>();
            for (Arg a : p.args()) args.add(Json.obj("name", a.name(), "description", a.description(), "required", a.required()));
            out.add(Json.obj("name", p.name(), "title", p.title(), "description", p.description(), "arguments", args));
        }
        return Json.obj("prompts", out);
    }

    static Map<String, Object> get(Json.Args params) {
        String name = params.str("name");
        Json.Args args = new Json.Args(params.raw("arguments"));
        for (Prompt p : PROMPTS) {
            if (!p.name().equals(name)) continue;
            String text = p.template();
            for (Arg a : p.args()) {
                String v = args.str(a.name(), null);
                if (v == null && a.required()) throw new Json.ToolError("Prompt " + name + " needs \"" + a.name() + "\"");
                if (v == null) v = a.name().equals("robot") ? "config/robot.jsonc" : "(not given)";
                text = text.replace("{" + a.name() + "}", v);
            }
            return Json.obj("description", p.description(),
                    "messages", List.of(Json.obj("role", "user", "content", Json.obj("type", "text", "text", text))));
        }
        throw new Json.ToolError("No prompt " + name);
    }
}

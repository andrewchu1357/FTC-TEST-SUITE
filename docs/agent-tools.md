# FTC Test Suite agent API: tool reference

Generated from the server (`tools/ftc-sim-mcp --docs`); the guide is [agent-api.md](agent-api.md). Every tool takes one JSON object of arguments and returns JSON (plus a PNG for pictures). Positions are inches, headings degrees (field center origin, +x toward the blue wall, +y away from the audience, heading 0 along +x, counter-clockwise positive), unless a field says otherwise.

| Group | Tools |
|---|---|
| Project | [`project_info`](#project_info), [`list_files`](#list_files), [`read_file`](#read_file), [`write_file`](#write_file), [`search_code`](#search_code), [`docs`](#docs) |
| Code | [`compile_code`](#compile_code), [`validate_robot_config`](#validate_robot_config), [`validate_field_config`](#validate_field_config), [`parts_catalog`](#parts_catalog) |
| Simulation | [`sim_create`](#sim_create), [`sim_list`](#sim_list), [`sim_close`](#sim_close), [`sim_reset`](#sim_reset), [`sim_step`](#sim_step), [`sim_state`](#sim_state), [`sim_record`](#sim_record), [`sim_image`](#sim_image) |
| OpModes | [`opmode_list`](#opmode_list), [`opmode_run`](#opmode_run), [`opmode_control`](#opmode_control), [`opmode_status`](#opmode_status), [`gamepad`](#gamepad) |
| Robot | [`robot_move`](#robot_move), [`hardware`](#hardware) |
| Field | [`game_pieces`](#game_pieces), [`match`](#match) |
| Debug | [`console_read`](#console_read), [`java_eval`](#java_eval), [`ui`](#ui) |

## Project

### project_info

**Project overview** (reads only)

Start here. The simulator project: where it is, its robot and field configs, robot packages, team code folders, docs, the coordinate system and units every tool uses, open sessions, and what this server allows.

No arguments.

### list_files

**List files** (reads only)

Lists project files under a folder (skipping build output and .git). Use a glob such as "**/*.java" to filter.

| Argument | Type | Description |
|---|---|---|
| `path` | string | Folder relative to the project (default: the project folder). |
| `glob` | string | Glob on the path relative to that folder, e.g. "**/*.java" or "*.jsonc". |
| `max` | integer | Most entries to return (default 500). |

### read_file

**Read a file** (reads only)

Reads a text file in the project (robot code, configs, docs, logs). Long files come in pages: use start_line.

| Argument | Type | Description |
|---|---|---|
| `path` **(required)** | string | File path relative to the project (absolute paths inside it work too). |
| `start_line` | integer | First line to return, from 1 (default 1). |
| `max_lines` | integer | Most lines to return (default 2000). |
| `line_numbers` | boolean | Prefix each line with its number (default false). |

### write_file

**Write a file**

Creates or changes a text file in the project: robot code (TeamCode/src/main/java/...), configs, test OpModes. Give "content" for the whole file, or "edits" ([{old, new}], each old text found exactly once) to change parts. Sessions already open keep the code they compiled: create a new session (or call compile_code) to try the change. Some paths are read-only (see project_info).

| Argument | Type | Description |
|---|---|---|
| `path` **(required)** | string | File path relative to the project. |
| `content` | string | The whole new file. |
| `edits` | array of {old, new} | Replacements to make in the existing file, in order. |

### search_code

**Search files** (reads only)

Searches project files with a regular expression (Java regex) and returns matching lines with file and line number.

| Argument | Type | Description |
|---|---|---|
| `pattern` **(required)** | string | Regular expression, e.g. "hardwareMap\\.get\\(". |
| `path` | string | Folder relative to the project (default: the project). |
| `glob` | string | Only files whose path matches, e.g. "**/*.java" (default: all text files). |
| `ignore_case` | boolean | Case-insensitive (default false). |
| `max` | integer | Most matches (default 200). |

### docs

**Read these docs** (reads only)

This API's documentation: guide (workflows, conventions, recipes, troubleshooting), tools (every tool and argument), or readme (the simulator itself: configs, physics, robot packages).

| Argument | Type | Description |
|---|---|---|
| `topic` | `guide` \| `tools` \| `readme` | Which document (default guide). |

## Code

### compile_code

**Compile team code** (reads only)

Compiles a robot's team code (its robot package's code, else TeamCode/src/main/java) plus optional extra Java sources, without starting a simulation. Returns errors with file/line/column, files left out because they don't compile (the rest still runs, as in the app), the OpModes found, and hardware names the code uses that the robot config lacks.

| Argument | Type | Description |
|---|---|---|
| `robot` | string | Robot config whose code to compile (default config/robot.jsonc). |
| `sources` | array of {path, content} | Extra Java files compiled with the team code (test OpModes, new mechanisms). |

### validate_robot_config

**Check a robot config** (reads only)

Parses a robot config (a file, or JSONC text) the way the simulator does and reports errors and warnings, plus the parsed result (motors, servos, sensors, drivetrain, mechanisms...). Optionally checks the hardware names in the robot's code against it.

| Argument | Type | Description |
|---|---|---|
| `path` | string | Robot config file relative to the project (default config/robot.jsonc). |
| `text` | string | JSONC text to check instead of a file (read as if it were at "path"). |
| `check_code` | boolean | Also compare against the hardware names the robot's code uses (default true). |
| `full` | boolean | Return every parsed field (default false: a summary). |

### validate_field_config

**Check a field config** (reads only)

Parses a field config (file or JSONC text): start positions, season, obstacles, game pieces, warnings.

| Argument | Type | Description |
|---|---|---|
| `path` | string | Field config relative to the project (default config/field.jsonc). |
| `text` | string | JSONC text to check instead of a file. |

### parts_catalog

**Parts catalog** (reads only)

The motors, servos, wheels, slides, odometry pods and sensors robot configs can name by id ("part": "..."), with their specs. Filter by category or text; give an id for the full specs.

| Argument | Type | Description |
|---|---|---|
| `category` | `motors` \| `servos` \| `wheels` \| `slides` \| `odometryPods` \| `sensors` | Only this category. |
| `query` | string | Text to look for in the id, name or vendor. |
| `id` | string | One part's id: returns all its specs. |
| `robot` | string | A robot config whose own parts folders to include (default: built-in parts only). |

## Simulation

### sim_create

**Create a simulation**

Creates a headless simulation (no window) of one or two robots on a field and compiles their team code, plus any extra Java sources you give (test OpModes, new subsystems) without touching project files. Time is lockstep: it only moves inside tools such as sim_step, opmode_run and match, as fast as the computer allows, so runs repeat exactly. Returns the session id, OpModes per robot, start positions and setup messages (compile warnings, files left out, hardware names the code uses that the config lacks). Compiled code is cached, so making another session with the same code is quick.

| Argument | Type | Description |
|---|---|---|
| `robot` | string | Robot config file relative to the project (default config/robot.jsonc). |
| `robot_config` | string | The robot config as JSONC text instead (read as if it were the "robot" file, so its code folders resolve from there). |
| `sources` | array of {path, content} | Extra Java files compiled with robot 1's team code. |
| `start_position` | string | Robot 1's start position (field config name); default: the field's first. |
| `partner` | any | A second robot: true for another copy of robot 1 (its own copy of the code), or a robot config path. |
| `partner_start_position` | string | The partner's start position. |
| `robots` | array of {robot, robot_config, sources, start_position} | Full control instead of robot/partner: one entry per robot. |
| `field` | string | Field config relative to the project (default config/field.jsonc). |
| `field_config` | string | The field config as JSONC text instead. |
| `name` | string | Session id to use instead of s1, s2... |

### sim_list

**List simulations** (reads only)

Lists the open sessions (headless ones and "live" when the simulator window is serving agents) with their robots, time and OpModes.

No arguments.

### sim_close

**Close a simulation**

Stops a headless session's OpModes and frees it. Close sessions you no longer need (at most 16 can be open).

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `all` | boolean | Close every headless session. |

### sim_reset

**Reset the field**

Match setup without recompiling: stops OpModes, puts every robot back at its start position (power-cycled: encoders and IMU zeroed), re-stages the game pieces and resets the score. Optionally changes start positions first.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `start_positions` | object | New start positions by robot number, e.g. {"1": "Red Left", "2": "Red Right"}. |
| `clear_trail` | boolean | Forget the path drawn by sim_image (default true). |

### sim_step

**Advance time**

Lets simulated time run (OpModes, physics, game) and returns the new state. Stops early when "until" holds. In a headless session this takes as long as the computer needs; in the live session it waits in real time.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `seconds` | number | How much simulated time to run (default 1; up to 600). |
| `until` | `opmode_stopped` \| `opmode_running` \| `match_ended` \| `robot_stopped` | Stop as soon as this holds. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |
| `include` | array of string | State sections to return (as sim_state; default pose, opmode). |

### sim_state

**Read the simulation** (reads only)

Everything about the robots and field right now: pose (in, deg, in/s, deg/s), the OpMode (state, telemetry, errors, loop time), motors (power, encoder ticks, ticks/s, amps), servos, mechanisms, odometry devices, distance sensors, wheels, lights, cameras (what they see), battery and physics, game pieces, match phase and score. Pick sections with include, or "all". "raw" adds the simulator's whole snapshot in SI units.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |
| `include` | array of string | Sections: pose opmode motors servos mechanisms odometry sensors wheels lights cameras physics pieces match score gamepads raw, or all. Default: pose opmode motors servos mechanisms odometry sensors match score. |
| `all_robots` | boolean | Every robot's state (default: only "robot", or all when there are two and robot isn't given). |

### sim_record

**Record over time**

Runs the simulation for some seconds while recording channels every interval (on the physics thread: nothing is missed), and returns the table plus first/last/min/max of each channel. Use it to tune and check: a drive's path, a lift's travel, a flywheel's spin-up, motor currents, a sensor's readings. Channels: x y heading vx vy omega speed battery current opmode telemetry telemetry.CAPTION motor.NAME.power|duty|position|velocity|current servo.NAME.position|angle mechanism.NAME odometry.NAME.x|y|heading distance.NAME piece.ID.x|y|z|state score.red score.blue phase clock (NAME may be *, r2. prefix for robot 2).

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |
| `seconds` | number | How long to run (default 3). |
| `interval` | number | Seconds between rows (default 0.05). |
| `channels` | array of string | What to record (default: x y heading speed motor.*.power mechanism.*). |
| `until` | `opmode_stopped` \| `opmode_running` \| `match_ended` \| `robot_stopped` | Stop recording early when this holds. |
| `max_rows` | integer | Most rows to return (default 400; older rows are thinned out evenly). |
| `csv` | string | Also write the full table to this CSV file (relative to the project). |
| `rows` | boolean | Return the rows (default true; false for just the summary). |

### sim_image

**Picture of the field** (reads only)

Draws the field from above as the simulator's 2D view does: robots, game pieces, field elements, sensor rays, shots in the air, and the path each robot drove. Returns a PNG.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `view` | `top` \| `driver` \| `follow` | top (default; red wall on the left), driver (from robot's alliance wall), follow (close up behind the robot). |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |
| `size` | integer | Picture size in pixels (default 720, 200..1600). |
| `trail` | boolean | Draw the path the robots drove since this session's first picture (default true). |
| `zoom` | number | Follow view: how close (default 2.5). |

## OpModes

### opmode_list

**List OpModes** (reads only)

The robot's OpModes as the Driver Station lists them: name, group, AUTO or TELEOP, class, and the TeleOp an AUTO preselects.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |

### opmode_run

**Run an OpMode**

The one-call test: INITs an OpMode, lets init run, presses START, runs it (optionally driving the gamepads from a script) until it stops by itself, the 30 s AUTO timer stops it, or "seconds" pass, then STOPs it. Returns how it ended, any crash with the line of robot code, telemetry (final and as it changed), the start and end pose, loop time, what it printed, an optional recording of channels (see sim_record) and an optional picture.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |
| `opmode` **(required)** | string | OpMode name as on the Driver Station (or its class name). |
| `init_seconds` | number | Seconds between INIT and START (default 0.5). |
| `seconds` | number | Most seconds to run after START (default 30 for AUTO, 15 otherwise; up to 600). |
| `reset` | boolean | Reset the field first (robots to their start positions, pieces re-staged; default false). |
| `start_position` | string | Put this robot at this start position first (resets the field). |
| `gamepad` | array of {t, gamepad1, gamepad2, duration, neutral, robot} | Gamepad script: what the drivers do, by time after START. |
| `record` | array of string | Channels to record while it runs (as sim_record), e.g. ["x","y","heading","motor.*.power"]. |
| `record_interval` | number | Seconds between recorded rows (default 0.05). |
| `max_rows` | integer | Most recorded rows to return (default 400). |
| `stop` | boolean | Press STOP when it's still running at the end (default true; false leaves it running). |
| `auto_timer` | boolean | Stop an AUTO 30 s after START as the field does (default true). |
| `image` | boolean | Add a picture of the field at the end (default false). |

### opmode_control

**Driver Station buttons**

Presses the Driver Station buttons: init (with an OpMode name), start, stop, or init_start. After init it lets init code run for init_seconds. Use sim_step to let the OpMode run, and sim_state / opmode_status to watch it.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |
| `action` **(required)** | `init` \| `start` \| `stop` \| `init_start` | What to press. |
| `opmode` | string | OpMode to INIT (name or class name). |
| `init_seconds` | number | Seconds to let init run after INIT (default 0.5; 0 for none). |
| `auto_timer` | boolean | Stop an AUTO 30 s after START (default true). |

### opmode_status

**OpMode status** (reads only)

The robot's OpMode right now: state (IDLE, INIT, RUNNING, STOPPING, STOPPED), name, time since START, telemetry, the crash message (with the line of robot code) if it threw, warnings, loop time, rumble, and the gamepads as the robot sees them.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |

### gamepad

**Drive the gamepads**

Sets what the robot's gamepad 1 or 2 reports, with the SDK's field names (left_stick_x/y, right_stick_x/y, left/right_trigger: numbers; a b x y, cross circle square triangle, dpad_up/down/left/right, left/right_bumper, start back guide, options share ps, left/right_stick_button, touchpad: true/false). Pushing a stick up is left_stick_y = -1, as on a real gamepad. set keeps the state until changed; tap presses buttons for a moment (advancing time); script plays timed states while time advances; release lets go. INIT resets the gamepads, so set them after INIT. In the live session this overrides the real controllers until released.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |
| `action` | `set` \| `tap` \| `script` \| `release` | set (default), tap, script, release. |
| `pad` | integer | 1 (default) or 2. |
| `state` | object | Fields to set, e.g. {"left_stick_y": -0.5, "right_bumper": true}. |
| `neutral` | boolean | set: release everything else first (default false). |
| `buttons` | array of string | tap: the buttons to press, e.g. ["a"]. |
| `duration` | number | tap: seconds held (default 0.1); script: seconds to run in all (default: to the last step + 0.5). |
| `script` | array of {t, gamepad1, gamepad2, duration, neutral, robot} | script: timed states, t in seconds from now. |

## Robot

### robot_move

**Move a robot**

Puts a robot where a test needs it, in inches and degrees (field center origin, +x toward the blue wall, heading 0 along +x, counter-clockwise). place: picked up and set down (encoders keep their counts, the OpMode keeps running); reset: power-cycled at a pose (encoders and IMU zeroed, default its start position); start_position: changes its start position (resets the whole field); push: a hand pulls the robot toward a point (as when someone pushes it during a tuning OpMode) until release.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |
| `action` **(required)** | `place` \| `reset` \| `start_position` \| `push` \| `release` | What to do. |
| `x` | number | Inches. |
| `y` | number | Inches. |
| `heading` | number | Degrees. |
| `name` | string | start_position: its name. |
| `grab_x` | number | push: point on the robot to push, inches forward of its center (default 0). |
| `grab_y` | number | push: inches left of its center (default 0). |

### hardware

**Robot hardware**

The robot's hardwareMap as robot code sees it. list: every device name with its type. read: a device's readings (every getter: motor power, mode, encoder position, velocity, current; servo position; sensor distances (inches), colors, angles (degrees), IMU yaw/pitch/roll, Pinpoint/OTOS poses, Limelight results...). call: run any method on a device with arguments, e.g. setPower [0.5], setTargetPosition [1200], setMode ["RUN_TO_POSITION"], getDistance ["CM"]. Enum arguments go as their names. Calls made while an OpMode runs compete with it.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |
| `action` | `list` \| `read` \| `call` | list (default), read, call. |
| `name` | string | Device name (as in hardwareMap.get(..., "name")); read without one reads every device. |
| `method` | string | call: the method name, e.g. setPower. |
| `args` | array of any | call: arguments, converted to the method's parameter types. |

## Field

### game_pieces

**Game pieces**

The season's game pieces. list: every piece with id, kind, state (FLOOR, AIRBORNE, HELD by a robot, CONTAINED in a field element, OFF_FIELD) and position (inches; z is the height of its center). kinds: what can be spawned. spawn: a new piece on the tiles. move: put a piece on the tiles somewhere else. remove: take it off the field. launch: throw a piece from a point with a velocity (in/s), as a shooter would, to test scoring. give: load a piece into a robot's hopper (as a preload). human_player: the human player's action for an alliance (season-specific).

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `action` | `list` \| `kinds` \| `spawn` \| `move` \| `remove` \| `launch` \| `give` \| `human_player` | list (default), kinds, spawn, move, remove, launch, give, human_player. |
| `id` | integer | The piece (move, remove, launch, give). |
| `kind` | string | Piece kind for spawn / launch / give (see kinds). |
| `x` | number | Inches. |
| `y` | number | Inches. |
| `z` | number | launch: height in inches (default 12). |
| `vx` | number | Inches/second. |
| `vy` | number | Inches/second. |
| `vz` | number | launch: inches/second up. |
| `alliance` | string | human_player / launch: red or blue. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |
| `include_off_field` | boolean | list: also pieces off the field (default false). |

### match

**Play a match**

A full FTC match: field reset, 30 s AUTO, 8 s transition, 2:00 TELEOP, with each robot's OpModes started and stopped by the match clock as on a real field. run plays it to the end and returns the final score with the breakdown, ranking points, referee notes, and each robot's errors; start begins it (headless: then sim_step, with until match_ended); status reads the clock and score; abort stops it.

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `action` | `run` \| `start` \| `status` \| `abort` | run (default), start, status, abort. |
| `auto` | string | Robot 1's AUTO OpMode (omit: it sits still in AUTO). |
| `teleop` | string | Robot 1's TELEOP OpMode (omit: the TeleOp its AUTO preselects, if any). |
| `partner_auto` | string | Robot 2's AUTO. |
| `partner_teleop` | string | Robot 2's TELEOP. |
| `gamepad` | array of object | TELEOP gamepad script (as opmode_run's, t from the start of TELEOP). |
| `record` | array of string | Channels to record during the match (as sim_record). |
| `record_interval` | number | Seconds between recorded rows (default 0.25). |
| `image` | boolean | Add a picture of the field at the end (default false). |

## Debug

### console_read

**Read the console** (reads only)

What robot code and the simulator printed: System.out/err, RobotLog, OpMode INIT/START/STOP, crashes with stack traces, config warnings. Each line has a number: pass the last one as "since" to read only what's new.

| Argument | Type | Description |
|---|---|---|
| `since` | integer | Only lines numbered after this (default: the newest "limit" lines). |
| `limit` | integer | Most lines (default 200). |
| `contains` | string | Only lines containing this text. |
| `thread` | string | Only lines from threads whose name contains this (an OpMode's thread is "OpMode: <name>"). |

### java_eval

**Run Java in the simulator**

Compiles and runs Java inside the simulator, with full access to the session and the robot's team code classes, for anything the other tools don't cover: read any field, call team code (a subsystem, a path follower, a constants class), change physics, inspect hardware objects, script a whole test. The code is a method body; these are in scope: sim (Simulation), robot (SimRobot), hardwareMap, runner (OpModeRunner), physics (SimRunner), match (MatchController), session (SimContext), out (PrintStream, returned as "output"), project (Path), and advance(seconds) to let simulated time run. Return a value to get it back as JSON (records and maps come out whole). A lone expression is returned as is. Units here are meters and radians. Imported: java.util.*, com.qualcomm.robotcore.hardware.*, com.qualcomm.robotcore.eventloop.opmode.*, the FTC navigation units, org.ftctestbed.sim.* (and .game, .opmode).

| Argument | Type | Description |
|---|---|---|
| `session` | string | Session id from sim_create ("s1"...), or "live" for the simulator's window. Optional when only one session is open. |
| `robot` | integer | Which robot: 1 (default) or 2 for the partner. |
| `code` **(required)** | string | Statements (a method body; may return a value) or one expression. |
| `declarations` | string | Extra members for the snippet's class: helper methods, fields, nested classes. |
| `imports` | array of string | More imports, e.g. ["org.firstinspires.ftc.teamcode.subsystems.*"]. |
| `timeout_seconds` | number | Real seconds before it's interrupted (default 30, up to 600). |

### ui

**Simulator window**

Live session only (the simulator's window): screenshot the window as the person sees it (3D view included); show a view (3D, 2D, Code, Setup); pick a 3D camera; pause, resume, set the speed, or step the physics while paused; reload the window's robots and code (after editing TeamCode or a config: it recompiles).

| Argument | Type | Description |
|---|---|---|
| `action` **(required)** | `screenshot` \| `show` \| `camera` \| `pause` \| `resume` \| `speed` \| `step` \| `reload` \| `info` | What to do. |
| `view` | string | show: 3D, 2D, Code or Setup. |
| `camera` | string | camera: a 3D camera preset name (info lists them). |
| `speed` | number | speed: 0.25, 0.5, 1, 2 or 4. |
| `steps` | integer | step: physics steps of 2 ms (default 50). |

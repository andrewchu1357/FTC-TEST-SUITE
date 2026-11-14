# Architecture

How the simulator is put together, for anyone changing it. For *using* it, see the
[user guide](../README.md).

## The big picture

```
 Team code (TeamCode/, robots/<name>/code)        unmodified FTC Java, Java 8
        │  compiled against
        ▼
 sdk-shim   FTC SDK stand-in: same class names and signatures (com.qualcomm.*, org.firstinspires.*)
        │  hardwareMap.get(...) hands out simulated devices; time calls go to simulated time
        ▼
 sim-core   physics (dyn4j), motors, mechanisms, sensors, game pieces, season rules,
        │   OpMode and match runners: no UI, so everything also runs headless
        ▼
 viewer     the window (Swing + JavaFX 3D), controllers, Code tab, browser view,
            headless runner, agent API (MCP)
```

| Module | Package root | What lives there |
|---|---|---|
| `sdk-shim/` | `com.qualcomm.*`, `org.firstinspires.ftc.*`, `android.*`, `org.ftctestbed.shim` | The FTC SDK API (OpModes, `HardwareMap`, motors, servos, sensors, IMUs, Limelight, Pinpoint, OTOS, OctoQuad, VisionPortal/AprilTag, `Telemetry`, `Gamepad`), stand-ins for FTC Dashboard (`com.acmerobotics.dashboard`) and Panels (`com.bylazar`), and the sim hooks and clock agent. Compiled at Java 8 like the real SDK. |
| `sim-core/` | `org.ftctestbed.sim` | `Simulation`, `SimRobot`, `SimRunner`, physics, simulated hardware, configs, in-app compiling, OpModes, game and seasons, vision, calibration. |
| `viewer/` | `org.ftctestbed.viewer` | `ViewerMain` (entry point), `MainWindow`, the UI kit, the 3D view, the 2D view, controllers, the Code tab, the web servers, `HeadlessMain`, and the agent API. |
| `TeamCode/` | `org.firstinspires.ftc.teamcode` | BuzzBot, the calibration OpModes, the starter examples, and most tests. Java 8, so it also builds in Android Studio. |
| `demo/` | `org.ftctestbed.viewer.demo` | The self-running demo app (`DemoMain` drives `MainWindow` by reflection). |
| `robots/` | | Robot packages: config, code, CAD model. |
| `tools/` | | `BuzzBotCad.java` (the CAD generator), `ftc-sim-mcp` and `ftc-sim-mcp.cmd` (agent launchers), `find-java.cmd` (finds or downloads Java 21 on Windows), `mac-release.sh` (builds and checks the Mac release). |
| `packaging/macos/` | | `IconGen.java`, which draws the app icon. |
| `packaging/windows/` | | `SingleExe.cs`, the one-file Windows launcher (`windowsExe`). |
| `gradle/windows-app.gradle` | | How the Windows app folders are packaged. |
| `viewer/…/platform/` | `org.ftctestbed.viewer.platform` | What differs between systems, in one place: `Os` (which key starts a shortcut, how shortcuts are written, the fixed-width font), `Win32` (the few things asked of Windows itself), `AppHome` (a packaged app's working folder, starter files and log), `DevLaunch` (the from-source launcher on Windows). |

## How team code runs

1. **Compile.** The window compiles the project's TeamCode with `javac` when it opens
   (`RobotCode.forProjectTeamCode` → `build/sim-teamcode`). Robot packages compile from
   `robots/<name>/code`. Files that don't compile are left out with a warning, so one broken
   file doesn't stop the rest. Code is compiled as `-source/-target 8`, like the Android
   toolchain.
2. **Load.** Each robot's code gets its own child-first `TeamCodeClassLoader`, so a partner robot
   running the same code doesn't share its static fields (`PoseStorage` and the like).
   Simulator, physics, JavaFX, Jamepad and JUnit classes always come from the parent.
3. **Discover.** `OpModeRegistry` finds `@TeleOp`/`@Autonomous`/`@Disabled` classes,
   `@OpModeRegistrar` methods and `@Utility` OpModes.
4. **Hardware.** `robot.jsonc` describes the hubs, motors, servos and sensors. `hardwareMap`
   returns simulated devices from `sim-core/hardware` behind the SDK's wrapper classes
   (`Rev2mDistanceSensor`, `RevColorSensorV3`, `BHI260IMU`…). Register-level I2C devices
   (Pinpoint, OTOS) talk to simulated firmware through `SimI2cDevice`.
5. **Run.** `OpModeRunner` runs the OpMode on its own thread: INIT, START, STOP, and
   `LinearOpMode` interruption, the same lifecycle as on a Control Hub.

## Time

Robot code must see *simulated* time, or `sleep(500)` and `ElapsedTime` would drift from the
physics.

- **Clock agent.** `sdk-shim/build/agent/sim-clock-agent.jar` is a `-javaagent`. `ClockAgent`
  and `ClockRewriter` rewrite `System.nanoTime/currentTimeMillis`, `Thread.sleep` and friends in
  team code and its libraries to call `SimTime`. Every Gradle `JavaExec`/`Test` task and every app
  launcher loads it. It skips classes from the boot and platform loaders. Rewriting those once
  broke every macOS drop-down with `NoClassDefFoundError`.
- **Physics step.** `Simulation.DT` is 2 ms. `SimRunner` owns the physics thread.
  - *Real time* (the window): steps follow the wall clock times a speed factor. Robot code runs
    freely, as on a Control Hub.
  - *Lockstep* (headless, tests, agent sessions): physics runs as fast as it can, but waits after
    each step for each robot thread to reach its next hardware call, sleep or idle. The same
    test gives the same result every time. `SimHarness` fails after 60 s of stalled simulated
    time instead of hanging.
- **Windows' timer.** The real-time loop waits 2 ms between steps. Windows' usual timer ticks
  every 15.6 ms, so on Java 21 that wait lasted 15 ms: the loop ran 7.5 steps in a burst each
  time it woke, robot code saw time jump, and the window's 60-a-second redraw ran at 37.
  `SimRunner` tells a listener when it starts and stops pacing against the wall clock
  (`setPacingListener`), and on Windows the viewer asks for the 1 ms timer meanwhile
  (`platform/Win32`: `timeBeginPeriod`, and on Windows 11 the request is kept while the window
  is minimized). Measured with it: one step each time the loop wakes, 1.7 ms apart. Lockstep
  runs and tests never ask for it. (Java 25 asks Windows for a finer timer by itself; Java 21,
  which the apps bundle, doesn't.)
- **Hub timing.** Each hardware call costs simulated time (`HubTiming`, `hubTiming` in
  `robot.jsonc`), the same as a REV hub's bus. The OpMode thread keeps its own "robot time" that
  calls push forward. That's why loop times match a real robot (tens of ms) and show in the
  window.
- **Determinism.** On every field reset, each sensor's random number generator is reseeded and
  the physics world is rebuilt, so a replayed match plays out identically. `SimSeed`
  (`-Dtestbed.seed=N`, or `SimSeed.set(N)` before a simulation is built) moves every random
  stream at once, and the game pieces' random variation, to another repeatable one: offset 0 is
  how it has always run. A device's fixed personal errors (a gyro's scale error) don't move.
  Repeatability holds once the JVM is warm: robot code that is still cold (classes loading, nothing
  compiled) can be too slow for lockstep to wait for it every step, so the first match or two in a
  JVM can come out a little differently.

## Physics (`sim-core/physics`)

- `FieldWorld`: the [dyn4j](https://dyn4j.org) world with field walls, field elements, robots
  and collision filters. The field is 141.17 in between the inside faces of the wall rails, from
  FIRST's CAD, not the nominal 144 in.
- `MecanumDrivetrain`: mecanum wheels with grip, rotor inertia, weight transfer (the center of
  gravity rises with the lift), rolling resistance on the tiles, and a wheel-lift warning.
- `MotorPhysics` and `Mechanism1D`: DC motors with stall torque, back-EMF and rotor inertia, and
  lifts, arms and flywheels.
- `Battery`: internal resistance (0.1 Ω default), state of charge and a filtered voltage.
- `OdometryPodSim`: dead wheels with diameter and mount errors, seams, slip and impacts.
- Game pieces (`game/`) fly in 2.5D with height, drag (Cd 0.47) and Magnus spin. They bounce off
  robots and land on `Contact` surfaces.

## Sensors and vision

`sim-core/hardware` has the IMUs (BHI260AP at 800 Hz, BNO055 at 100 Hz, with drift, noise and
scale error), distance sensors (33 ms updates), color sensors, encoders (REV 16-bit velocity
wrap), Limelight 3A, webcams, Pinpoint, OTOS and OctoQuad. `sim-core/vision` builds camera
frames from field geometry (`VisionScene`, `Fiducial`, `CameraModel`). The shim's
`org.ftctestbed.shim.vision` package runs the SDK's AprilTag and color processors on those
frames (`PnpSolver`, `BlobGeometry`). Orientation math is ported from the SDK and gives the same
results as the real SDK, value for value.

## Game and season rules

`game/` holds what every season shares: `GamePiece`/`GamePieces`, robot piece handling (intake,
hopper, launcher, claw), `MatchController` (AUTO, transition, TELEOP, endgame) and `RobotView`
(what a season may know about a robot). The rules for one season live in a season plug-in:
`season/biobuzz` (`BioBuzzSeason`, `Hive` as a rigid body that tips, `HiveFrame`, `Flower`,
`BioBuzzSnapshot` with the full score and the TU05 referee notes and cards). A new season adds a
package there and a `field.jsonc`.

## Several robots

`Simulation.robots()` holds every robot on the field. The old single-robot API (`hardwareMap()`,
`snapshot()`, `resetRobot()`…) means robot 1, so the 2D view, the web view and headless runs
still work unchanged. Each robot has its own OpMode thread and robot time in `SimRunner`, its own
class loader, its own Driver Station and its own controller slots (slots 0–1 drive robot 1,
slots 2–3 drive robot 2).

## The window (`viewer/`)

- **Shell.** Swing with FlatLaf, styled as Fluent (Windows 11): `ui/AppShell`, `NavRail`
  (3D / 2D / Code / Setup), `CommandBar`, and the Driver Station pane docked on the right. There
  are 11 themes (`Appearance`, `Theme`), and `ThemeTest` checks contrast on every surface.
- **3D.** JavaFX inside a `JFXPanel` (`three/`): `View3D`, `Field3D`, `Robot3D`, `OrbitCamera`
  presets (Driver, Audience, Top, Stands, Follow robot), `Stadium` (crowd), `Textures`,
  `Overlay3D` (Dashboard and Panels drawings). See-through things are drawn last, farthest
  first, because JavaFX doesn't sort transparent objects by depth.
- **2D.** `FieldPanel` and `FieldRenderer` with cached layers.
- **Input.** `PhysicalGamepads` (Jamepad/SDL2: PS4, PS5, Xbox, F310), `KeyboardGamepad`, and
  `DriverStation` (100 Hz, with the real Driver Station's dead zones and delay).
- **Code tab.** `CodePanel`, `CodeSession` and `CodeWorkspace` (RSyntaxTextArea). ⌘R saves,
  checks the code compiles, then reloads the window and runs the OpMode.
- **Each system's own ways** (`platform/`). Shortcuts are written once, the way a Mac shows
  them (`Os.stroke("⇧⌘R")`, `Os.text("… (⌘P)")`), and become Ctrl+Shift+R and Ctrl+P on
  Windows; the four with ⌥ get their own keys there (F5 for Reload Robot), since Ctrl+Alt is
  AltGr. `OsTest` checks that no two shortcuts collide on either system. On a Mac the app draws
  its own title bar over the full window; on Windows that bar is hidden and FlatLaf's Windows
  title bar is used instead, with the menu bar and the status in it, so Windows does the
  moving, snapping and maximizing. `Appearance` reads the system's dark mode, transparency,
  contrast and power settings from macOS (`defaults`, `pmset`) or from Windows (`Win32`: the
  registry and `GetSystemPowerStatus`, through JNA, which is never loaded on other systems).
- **Servers.** `SimWebView` (8766, view only) and `AgentService` (8767).

## Threading rules

These rules come from deadlocks that actually happened:

- Robot code waits for simulated time *inside* synchronized device methods. So nothing that holds
  the `Simulation` lock may call a device method while an OpMode runs, and neither may the
  lockstep harness thread.
- A simulated device must never wait for simulated time while it holds its own lock, if the
  physics thread also takes that lock. Wait first, then synchronize. If you break this, the
  window shows "Simulated time stopped at 0.000 s".
- `OpModeRunner` tells its listeners an OpMode finished *before* it switches to STOPPED.
  Cameras close in that gap.

## Things Windows does differently

Found while making the Windows versions, and worth knowing before changing nearby code:

- **A pattern isn't a path.** `*` can't be in a Windows file name, so `Path.resolve("libs/*.jar")`
  throws there. Split the folder from the pattern first (`RobotCode.libs`). This one bug kept
  every robot package from compiling on Windows.
- **A running program's jars are locked.** Nothing can replace or delete a `.jar` a JVM has
  open. That's why the from-source launcher is a program of its own (`DevLaunch`), and why a
  build fails while the simulator window is open.
- **A file that was just written may be held for a moment** by a virus scanner, the search
  indexer or a cloud-sync client; its folder then still counts as not empty. Deleting a tree
  tries again a few times (`RobotCode.deleteTree`, `AppHome`). A read-only file can't be deleted
  until the mark is taken off.
- **Paths print with `\`.** Anything shown to people or compared in a test as a project path is
  written with `/` (`CodeWorkspace` root labels, the agent API).
- **Consoles aren't UTF-8.** `HeadlessMain` switches its output (`Win32.utf8Output`), or the
  score's ✓ and · print as ?.
- **Listening on the network brings up the firewall prompt**, which needs an administrator. See
  `allowNetworkOnWindows` in [Configuration](configuration.md).
- **An unsigned `.exe` may not be allowed to run.** Windows 11's Smart App Control judges it by
  the exact file's reputation, so the launchers are kept byte for byte the same between builds
  and every app folder can also be started through Java's own signed `javaw.exe`. See
  [Building: Smart App Control](building.md#smart-app-control) before changing
  anything that alters the `.exe` files (their version, icon or name).

## Coordinates and units

Inches and degrees. The origin is the field center. +x points toward the blue alliance wall
and +y away from the audience. Heading 0 points along +x, and counter-clockwise is positive.
The agent API, configs and headless output all use this convention.

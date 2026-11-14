# Building, running and packaging

FTC Test Suite runs on **Windows 10 and 11** and on **macOS** (12 or later). The release
downloads need nothing installed. To build it yourself:

- **Windows:** double-click `Run Simulator.bat`. It finds a JDK 21 on the PC, or downloads one
  into your user profile (no installer, no administrator), then builds and opens the simulator.
- **macOS:** install a JDK 21 (for example `brew install openjdk@21`; the build finds Homebrew's
  by itself), then `./gradlew run`.

For the commands below you need a **JDK 21** (a JDK, not a JRE: the app compiles team code).
Gradle comes with the project (`gradlew`, `gradlew.bat`) and downloads the libraries on the first
build; it can also fetch a JDK 21 by itself if it finds none. On Windows, write `gradlew` (or
`.\gradlew` in PowerShell) where this page says `./gradlew`.

```bash
export JAVA_HOME=/path/to/jdk-21        # Windows PowerShell: $env:JAVA_HOME = "C:\path\to\jdk-21"
./gradlew run                           # Windows: gradlew run
```

## Everyday tasks

| Command | What it does |
|---|---|
| `./gradlew run` | Opens the simulator window. |
| `./gradlew installDist` | Builds the app into `viewer/build/install/viewer/`. |
| `./gradlew test --rerun-tasks` | Runs every test, about 290 of them. Without `--rerun-tasks`, Gradle skips tests whose inputs didn't change and reports them as UP-TO-DATE. |
| `./gradlew :TeamCode:test --tests '*SoakTest'` | Runs one test class. |
| `./gradlew headless --args="…"` | Runs without a window. See below. |
| `./gradlew checkShim` | Checks that the libraries TeamCode uses (FTCLib, Road Runner, Pedro…) only call SDK APIs the shim provides, and lists the ones it doesn't. |
| `./gradlew calibrate --args="logs/calibration/drive-….csv"` | Fits the robot model to logs from the Calibrate OpModes run on the real robot. Writes `config/robot.calibrated.jsonc`. |
| `./gradlew :TeamCode:launcherSweep` | Test-shoots BuzzBot at the HIVE from many distances and prints which flywheel speeds score. |
| `./gradlew :TeamCode:autoTrace --args="'Red Wall' 'BuzzBot Auto Red'"` | Runs an auto and prints the pose, hopper and telemetry every 0.25 s. |
| `java tools/BuzzBotCad.java` | Rebuilds BuzzBot's CAD model and `docs/buzzbot-build.md`. |
| `tools/ftc-sim-mcp --docs` | Prints the agent tool reference (`tools\ftc-sim-mcp.cmd --docs` on Windows). |

## Headless runs

```bash
./gradlew -q headless --args="--match --auto 'BuzzBot Auto Red' --teleop 'BuzzBot TeleOp' --start 'Red Wall'"
```

Plays a full match in a few seconds and prints the score breakdown and the referee notes. On
Windows run it from PowerShell (`.\gradlew.bat …`) or `cmd`: Git Bash passes quoted arguments to
`cmd` in a way that breaks on a folder name with a space.

| Option | Meaning |
|---|---|
| `--match` | Plays a full match (AUTO, then TELEOP) |
| `--auto NAME`, `--teleop NAME` | The OpModes for the match |
| `--opmode NAME` | Runs one OpMode instead of a match |
| `--start NAME` | Start position, e.g. `'Red Wall'`, `'Blue Audience'` |
| `--seconds N` | How long to run a single OpMode |
| `--realtime` | Paces the run to the wall clock instead of lockstep |
| `--log FILE` | Writes a CSV log of the robot's pose |
| `--snapshot FILE.png` | Saves a picture of the field at the end |
| `--driver-view` | Turns the snapshot to the driver's view |
| `--robot FILE`, `--field FILE` | Uses another `robot.jsonc` or `field.jsonc` |

Headless runs are lockstep and repeatable: the same command gives the same result every time.

## Tests

Most tests live in `TeamCode/src/test/java/...` (they run real OpModes) and
`sim-core/src/test/java/...` (physics, seasons, configs). The `viewer` and `sdk-shim` modules
have their own. A test looks like this:

```java
try (SimHarness sim = SimHarness.load()) {
    sim.startAt("Red Wall").runMatch("BuzzBot Auto Red", null);
    assertEquals(1, ((BioBuzzSnapshot) sim.season()).red().tipsAuto());
}
```

Tests and headless runs don't open a window. The window's look is tested by drawing it off
screen. The suites pass on Windows 11 and on macOS. Linux isn't tested by the maintainers.

## Packages

None of these needs an installer or an administrator, and each carries its own Java, so the
computer it runs on needs nothing installed. Each is built on the system it is for (the JDK's
packager only makes launchers for the system it runs on). `-PappVersion=1.0.0` names the version
(it's the day's date otherwise).

### Windows

| Command | Output |
|---|---|
| `gradlew windowsApp` | `viewer\build\windows\FTC Test Suite\`, with `FTC Test Suite.exe` and its own Java |
| `gradlew windowsZip` | `viewer\build\dist\FTC-Test-Suite-Windows.zip` (about 85 MB): that folder, to share |
| `gradlew windowsExe` | `viewer\build\dist\FTC-Test-Suite-<version>.exe`: the same app in one file (see below) |
| `gradlew :demo:demoWindows` | `viewer\build\dist\FTC-Test-Suite-Demo-Windows.zip`: a self-running showcase for events |
| `gradlew installWindowsApp` | Start menu and Desktop shortcuts to `Run Simulator.bat` for this project |

Unzip anywhere and double-click the `.exe`. What the package does:

- The `.exe` is the JDK's own launcher (jpackage), so the taskbar and Task Manager show the app.
  Java 21 is in `runtime\`, linked with jlink, and every native library (JavaFX, FlatLaf, JNA) is
  already unpacked in `app\bin`.
- The app works in `%USERPROFILE%\FTC Sim\Workspaces\App` (the demo: `…\Workspaces\Demo`, emptied
  at every launch), and `--home "E:\My Robot"` after the `.exe`'s name picks another folder.
  The starter files (config, TeamCode, robots) are inside the app and are copied in when a version
  of the app first opens the folder; a file that's already there is never replaced, so a newer zip
  can be unzipped over an older one.
- `ftc-sim.exe` runs headless matches from a terminal on the same Java. The log is
  `%LOCALAPPDATA%\FTC Test Suite\Logs\FTC Test Suite.log`.
- The window follows Windows: its title bar (with the menus in it), Ctrl for ⌘ in every shortcut,
  light/dark mode, transparency, contrast themes, battery saver, and the 1 ms timer while the
  simulation runs. The two web pages stay on this computer unless `allowNetworkOnWindows` is set
  (see [Configuration](configuration.md)), because opening a page to the network makes Windows ask
  for an administrator.

#### The one-file .exe

`gradlew windowsExe` wraps the zip in one file for a download page: a small launcher
(`packaging/windows/SingleExe.cs`, compiled with the C# compiler that is part of Windows) with the
zip inside. The first start of each version unpacks the app into
`%LOCALAPPDATA%\FTC Test Suite\app\` (a few seconds, with a progress window) and removes older
versions there; every start then runs `FTC Test Suite.exe` from that folder, with the launcher's
arguments (`--home` works). If Windows refuses that `.exe`, it runs `Start FTC Test Suite.cmd`
instead. `--unpack-only` unpacks without a window and stops. Its bytes are new with every build,
so Smart App Control can refuse the launcher itself: publish it next to the zip, not instead of it.

#### Smart App Control

Some Windows 11 PCs have Smart App Control on. It lets a program without a publisher's signature
run only if Microsoft's cloud service thinks well of that exact file, and there is no "Run anyway".
These apps are not signed, so it can refuse the `.exe`. Two things help:

1. The `.exe` files in the zip are byte for byte the same in every build (the version number
   stamped into them is fixed: `LAUNCHER_VERSION` in `gradle/windows-app.gradle`; the real version
   is in the `.cfg` files), so a file keeps the standing it has earned.
2. Every app folder also has **`Start FTC Test Suite.cmd`**, which runs `runtime\bin\javaw.exe`
   directly. Java's own program is signed by its publisher, so Windows runs it.

Files unzipped from a download carry a "from the internet" mark, and Smart App Control refuses
`.cmd` files with that mark: right-click the zip ▸ Properties ▸ **Unblock** before unzipping.
Never turn Smart App Control off for this (it can't be turned back on without reinstalling
Windows). A code-signing certificate is the real fix. Where an organization allows only approved
programs to run from folders a user can write to, nothing portable can start.

### macOS

| Command | Output |
|---|---|
| `./gradlew macDmg` | `viewer/build/dist/FTC Test Suite.dmg` (about 85 MB, Apple silicon): the whole app with its own Java |
| `tools/mac-release.sh 1.0.0` | The same DMG as `FTC-Test-Suite-macOS-1.0.0.dmg`, checked, with a `.sha256` file: see [Releases](#releases) |
| `./gradlew installMacApp` | `~/Applications/FTC Test Suite.app`: a launcher for this project that rebuilds when the code changed |
| `./gradlew :demo:demoApp` | `viewer/build/dist/FTC Test Suite Demo.app`: the self-running showcase |

- Open the DMG and drag **FTC Test Suite** to Applications. It needs a Mac with Apple silicon
  (M1 or later) and macOS 12 or later; on an Intel Mac, run it from source (`./gradlew run`).
- Java 21 is inside the app, linked with jlink from Eclipse Temurin (Gradle downloads that JDK the
  first time it builds the DMG).
- The app works in `~/FTC Sim/Workspaces/App`, filled from starter files the first time and never
  overwritten. The log is `~/Library/Logs/FTC Test Suite.log`.
- The app is signed ad hoc only (no Apple Developer ID), so the first open is blocked: click
  **Done**, then System Settings ▸ Privacy & Security ▸ **Open Anyway**. Once is enough. An Apple
  Developer ID and notarization would remove that step.

### Linux

`./gradlew run` is expected to work (the build picks JavaFX's Linux libraries), but it isn't
tested by the maintainers and there is no package for it yet.

## Releases

Build each system's files on that system, from the same commit, with the same version:

- **Windows:** `gradlew windowsExe -PappVersion=1.0.0` makes both the zip
  (`viewer\build\dist\FTC-Test-Suite-Windows.zip`, rename it `FTC-Test-Suite-Windows-1.0.0.zip`)
  and `FTC-Test-Suite-1.0.0.exe`.
- **macOS:** `tools/mac-release.sh 1.0.0` makes `FTC-Test-Suite-macOS-1.0.0.dmg` and checks it: it
  mounts the DMG, checks the app's version, launcher and signature, and plays a headless match with
  the Java inside the app. It writes the DMG's checksum next to it.

Attach the files (and their checksums) to a GitHub release for the tag `v1.0.0`. Built files
don't go in the repository.

## Build outputs

Everything generated goes into `build/` folders, which are safe to delete:

| Path | What |
|---|---|
| `viewer/build/install/viewer/` | The installed app (`lib/` jars and `bin/viewer`) |
| `sdk-shim/build/agent/sim-clock-agent.jar` | The simulated-time agent, loaded by every run and test task |
| `build/sim-teamcode/`, `robots/*/build/` | Team code compiled by the window at launch |
| `viewer/build/dist/` | Release packages (zip, `.exe`, DMG) |
| `viewer/build/windows/`, `viewer/build/dmg-stage/` | The Windows app folder and the staged Mac app |
| `build/windows/` | What `Run Simulator.bat` runs (argument files, the launcher's classes) |
| `logs/` | Calibration logs and CSVs from headless runs (not build output; keep them) |

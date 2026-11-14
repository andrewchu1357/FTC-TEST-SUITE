# Third-party notices

FTC Test Suite is licensed under the Apache License 2.0 (see [LICENSE](LICENSE)). It uses the
software below, each under its own license. The libraries are downloaded by Gradle when you
build; the packaged apps (the Windows zips and `.exe`, the Mac DMG) contain them, and this file
goes with those packages.

## Libraries in the simulator

| Library | License |
|---|---|
| [dyn4j](https://dyn4j.org) 5.0.2: the physics engine | BSD-3-Clause |
| [FlatLaf](https://www.formdev.com/flatlaf/) 3.5.4 and its IntelliJ themes: the look of the window | Apache-2.0. Each bundled theme keeps its author's license (see the FlatLaf IntelliJ Themes documentation) |
| [RSyntaxTextArea](https://github.com/bobbylight/RSyntaxTextArea) 3.6.3: the Code tab's editor | BSD-3-Clause |
| [Jamepad](https://github.com/williamahartman/Jamepad) 2.30.0.0 and gdx-jnigen-loader: game controllers, through SDL2 | Apache-2.0 (SDL2 inside it: zlib license) |
| [OpenJFX](https://openjfx.io) (JavaFX) 21.0.12: the 3D view | GPL-2.0 with the Classpath Exception |
| [JNA](https://github.com/java-native-access/jna) 5.17.0: Windows system calls | Apache-2.0 (also offered under LGPL-2.1-or-later; used here under Apache-2.0) |
| [Eclipse Temurin](https://adoptium.net) JDK 21: the Java runtime inside the packaged apps | GPL-2.0 with the Classpath Exception (its `legal` folder is in the runtime) |
| Jackson (core, annotations, databind) 2.12.7 | Apache-2.0 |
| EJML 0.39 | Apache-2.0 |
| Kotlin standard library | Apache-2.0 |
| JetBrains annotations, Google JSR-305 annotations | Apache-2.0 |
| jnanoid 2.0.0 | MIT |
| NanoHTTPD 2.3.1 | BSD-3-Clause |

## Libraries robot code uses in the simulator (TeamCode)

These are what the example code and the tests run against, as in a real FTC project.

| Library | License |
|---|---|
| [Road Runner](https://rr.brott.dev) (core, actions, ftc) | MIT |
| [Pedro Pathing](https://pedropathing.com) 3 (core, revhub, tuning) and Ivy (core, pedro) | BSD-3-Clause |
| [FTCLib](https://docs.ftclib.org) 2.1.1 | MIT |

## FIRST sample code and quickstarts

- `sim-core/src/test/resources/sdk-samples-12.0/`: FIRST's FTC SDK 12.0 sample OpModes,
  unmodified, used by a test to check that they compile against the SDK stand-in. Copyright (c)
  FIRST; each file keeps its notice and BSD-style license in its header.
- `robots/RoadRunnerQuickstart/` and `robots/PedroQuickstart/`: the Road Runner and Pedro Pathing
  quickstart projects, unmodified, used by tests and as examples. Each folder has its own
  `LICENSE` (the FIRST BSD-style license) and keeps the notices in its files.

## The FTC SDK, in `sdk-shim`

The `sdk-shim` module re-implements the *API* of the FTC SDK (the same package, class and
method names, so that robot code compiles unchanged). It is mostly written for this project.
Some parts follow the SDK's own code so that results match it value for value (for example the
angle extraction in `Orientation`, "ported from SDK 12.0" in its comments). **FIRST's license
applies to those portions**, and it is the license below. If you find more code that was taken
from the SDK, it is under that license too.

```
Copyright (c) 2014-2022 FIRST.  All rights reserved.

Redistribution and use in source and binary forms, with or without modification,
are permitted (subject to the limitations in the disclaimer below) provided that
the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list
of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this
list of conditions and the following disclaimer in the documentation and/or
other materials provided with the distribution.

Neither the name of FIRST nor the names of its contributors
may be used to endorse or promote products derived from this software without
specific prior written permission.

NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE GRANTED BY THIS
LICENSE. THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Project data

The BIOBUZZ field and scoring are the authors' reading of FIRST's public game documents, and
dimensions are taken from them. They are modeling data, not FIRST's CAD or its manual's text.
The sounds are made by the simulator (no recordings are included). The BuzzBot model is generated
from published part dimensions (goBILDA and REV) by `tools/BuzzBotCad.java`, and ExampleBot's
model was made for this project too. The app icon is drawn by `packaging/macos/IconGen.java`, and
the pictures in `docs/` are of this app.

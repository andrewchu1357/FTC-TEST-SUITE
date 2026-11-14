@echo off
rem FTC Test Suite on Windows, run from this project folder: double-click this file. It's the
rem counterpart of the Mac app: it runs the code in this folder, and rebuilds first when the
rem simulator's own code changed (team code in TeamCode\ never needs a build).
rem
rem Nothing has to be installed first and nothing asks for an administrator:
rem   - Java: found on this computer, or downloaded once into your own profile (tools\find-java.cmd).
rem   - The first run builds the simulator with the Gradle wrapper in this folder, which downloads
rem     Gradle and the libraries once (a few minutes, needs the internet).
rem After that it starts in a moment, with no window but the simulator's.
rem
rem   "Run Simulator.bat"              start the simulator
rem   "Run Simulator.bat" --rebuild    build first, whatever changed
rem   "Run Simulator.bat" --robot robots\ExampleBot\robot.jsonc     (any of the app's own options)
rem
rem "gradlew installWindowsApp" adds Start menu and Desktop shortcuts that run this file.
rem What the app prints goes to %LOCALAPPDATA%\FTC Test Suite\Logs\FTC Test Suite.log
setlocal EnableExtensions
cd /d "%~dp0"

rem --shortcut: started from the Start menu or Desktop shortcut, in a minimized window.
set "FTC_MINIMIZED="
if /i "%~1"=="--shortcut" set "FTC_MINIMIZED=1"
if defined FTC_MINIMIZED shift /1
set "FTC_REBUILD="
if /i "%~1"=="--rebuild" set "FTC_REBUILD=1"
if /i "%~1"=="--rebuild" shift /1
if not exist "build\windows\launcher.args" set "FTC_REBUILD=1"
if not exist "build\windows\app.args" set "FTC_REBUILD=1"
if not exist "build\windows\launcher-classes" set "FTC_REBUILD=1"

if not defined FTC_MINIMIZED goto setup
rem Nobody sees a minimized window: if Java has to be downloaded or the simulator built first,
rem do that in a window that shows.
call "tools\find-java.cmd" --no-download
if errorlevel 1 goto visible
if defined FTC_REBUILD goto visible
goto run

:visible
start "FTC Test Suite" cmd /c ""%~f0" %1 %2 %3 %4 %5 %6"
exit /b 0

:setup
call "tools\find-java.cmd"
if errorlevel 1 goto failed
if not defined FTC_REBUILD goto run
echo Building FTC Test Suite. The first time takes a few minutes: Gradle and the libraries are downloaded once...
call gradlew.bat --console=plain -q windowsLauncher
if errorlevel 1 goto buildfailed
type nul > "build\windows\.built"

:run
set "FTC_LOG=%LOCALAPPDATA%\FTC Test Suite\Logs\FTC Test Suite.log"
rem PS4/PS5 controllers rumble over Bluetooth too.
set "SDL_JOYSTICK_HIDAPI_PS4_RUMBLE=1"
set "SDL_JOYSTICK_HIDAPI_PS5_RUMBLE=1"
rem javaw: no console window stays open next to the app. The launcher looks whether the code
rem changed (rebuilding with a small progress window if so), then starts the app itself.
start "FTC Test Suite" "%JAVA_HOME%\bin\javaw.exe" "-Dtestbed.log=%FTC_LOG%" @build\windows\launcher.args %1 %2 %3 %4 %5 %6
exit /b 0

:buildfailed
echo.
echo The build failed. What it said is above; "gradlew.bat windowsLauncher" in this folder runs it again.

:failed
echo.
pause
exit /b 1

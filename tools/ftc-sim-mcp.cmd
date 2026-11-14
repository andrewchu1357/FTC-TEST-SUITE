@echo off
rem FTC Test Suite agent API on Windows: the MCP server AI agents (Claude Code, Cursor, Codex...)
rem start to drive the simulator. The Windows counterpart of tools/ftc-sim-mcp, with the same options:
rem
rem   tools\ftc-sim-mcp.cmd                 MCP over stdio, headless simulations (the usual way)
rem   tools\ftc-sim-mcp.cmd --live          MCP over stdio, passed on to the open simulator window
rem   tools\ftc-sim-mcp.cmd --http [PORT]   MCP + REST over HTTP on 127.0.0.1 (token: FTC_SIM_TOKEN or --token)
rem   tools\ftc-sim-mcp.cmd --docs          print the tool reference
rem
rem It finds Java by itself (downloading it once if the computer has none: tools\find-java.cmd)
rem and builds the simulator first when its code changed. Everything it says about that goes to
rem stderr: stdout carries only MCP messages. Guide: docs\agent-api.md
setlocal EnableExtensions
cd /d "%~dp0.."

call "tools\find-java.cmd" --quiet
if errorlevel 1 exit /b 1

if exist "build\windows\agent.args" if exist "build\windows\agent-check.args" if exist "build\windows\launcher-classes" goto check
echo ftc-sim-mcp: building the simulator... 1>&2
call gradlew.bat --console=plain -q windowsLauncher 1>&2
if errorlevel 1 goto buildfailed
type nul > "build\windows\.built"
goto serve

:check
rem Builds again if the simulator's code changed since the last build (a tenth of a second if it didn't).
"%JAVA_HOME%\bin\java.exe" @build\windows\agent-check.args
if errorlevel 1 goto buildfailed

:serve
"%JAVA_HOME%\bin\java.exe" @build\windows\agent.args --project "%CD%" %*
exit /b %errorlevel%

:buildfailed
echo ftc-sim-mcp: the simulator didn't build (see above) 1>&2
exit /b 1

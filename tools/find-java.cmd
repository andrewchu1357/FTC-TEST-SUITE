@echo off
rem Finds a Java 21 JDK for the Windows launchers and puts its folder in JAVA_HOME. If the computer
rem has none, it downloads one (Eclipse Temurin 21, about 200 MB, once) into
rem "%LOCALAPPDATA%\FTC Test Suite\jdk". Nothing is installed and nothing needs an administrator:
rem the download is a zip unpacked into your own profile, and deleting that folder removes it.
rem
rem   call tools\find-java.cmd                 sets JAVA_HOME; errorlevel 1 (after a message) if it can't
rem   call tools\find-java.cmd --quiet         the same, but messages go to stderr (for programs that
rem                                            read this window's output, like the agent server)
rem   call tools\find-java.cmd --no-download   only looks: errorlevel 1, silently, if there is none
rem
rem Looked at, in this order: a JDK 21 this script downloaded earlier, JAVA_HOME, the usual places
rem JDKs are installed, java on the PATH; then a newer JDK from JAVA_HOME or the PATH (22 and later
rem run the simulator too, but Gradle then fetches a 21 to compile with).

set "FTC_QUIET="
set "FTC_NO_DOWNLOAD="
if /i "%~1"=="--quiet" set "FTC_QUIET=1"
if /i "%~1"=="--no-download" set "FTC_NO_DOWNLOAD=1"
set "FTC_JDK="
set "FTC_JDK_NEWER="
set "FTC_DATA=%LOCALAPPDATA%\FTC Test Suite"
if not defined LOCALAPPDATA set "FTC_DATA=%USERPROFILE%\AppData\Local\FTC Test Suite"
rem Windows' own curl, tar, certutil and PowerShell, by their full paths: a "tar" from Git or
rem another toolkit earlier on the PATH reads the "C:" in a file name as another computer's name.
set "FTC_SYS=%SystemRoot%\System32"
set "FTC_PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

rem (set FTC_SIM_TEST_NO_JAVA=1 to try the download on a computer that has Java)
if defined FTC_SIM_TEST_NO_JAVA goto download

for /d %%d in ("%FTC_DATA%\jdk\jdk-21*") do call :consider "%%~fd"
if defined JAVA_HOME call :consider "%JAVA_HOME%"
for %%r in ("%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Java" "%ProgramFiles%\Microsoft" "%ProgramFiles%\Zulu" "%ProgramFiles%\Amazon Corretto" "%ProgramFiles%\BellSoft" "%USERPROFILE%\.jdks" "%USERPROFILE%\.gradle\jdks" "%USERPROFILE%\scoop\apps\temurin21-jdk") do (
  if exist "%%~r" for /d %%d in ("%%~r\*21*") do call :consider "%%~fd"
)
for %%j in (javac.exe) do if not "%%~$PATH:j"=="" for %%b in ("%%~dp$PATH:j..") do call :consider "%%~fb"
if defined FTC_JDK goto found
if defined FTC_JDK_NEWER goto newer

:download
if defined FTC_NO_DOWNLOAD goto fail
set "FTC_URL=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%%2B9/OpenJDK21U-jdk_x64_windows_hotspot_21.0.13_9.zip"
set "FTC_SHA=a4dbaa05fca4bd89d84584e58c459bd732efebb1722af80ab13c6516832004f9"
set "FTC_ZIP=%FTC_DATA%\jdk\jdk21.zip.part"
call :say "Java 21 isn't on this computer. Downloading it once (about 200 MB) into"
call :say "  %FTC_DATA%\jdk"
call :say "Nothing is installed and no administrator is needed."
if not exist "%FTC_DATA%\jdk" mkdir "%FTC_DATA%\jdk"
if not exist "%FTC_DATA%\jdk" goto nofolder

set "FTC_GOT="
if not exist "%FTC_SYS%\curl.exe" goto psdownload
if defined FTC_QUIET "%FTC_SYS%\curl.exe" -L --fail --silent --show-error -o "%FTC_ZIP%" "%FTC_URL%" 1>&2
if not defined FTC_QUIET "%FTC_SYS%\curl.exe" -L --fail --progress-bar -o "%FTC_ZIP%" "%FTC_URL%"
if not errorlevel 1 set "FTC_GOT=1"
if defined FTC_GOT goto downloaded

:psdownload
rem Older Windows 10 has no curl: PowerShell downloads it.
"%FTC_PS%" -NoProfile -NonInteractive -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest -UseBasicParsing -Uri $env:FTC_URL -OutFile $env:FTC_ZIP" 1>&2
if not errorlevel 1 set "FTC_GOT=1"
if defined FTC_GOT goto downloaded
call :say "The download didn't work. Check the internet connection and try again, or use the"
call :say "FTC Test Suite zip for Windows instead: it has Java inside and needs no download."
del "%FTC_ZIP%" >nul 2>nul
goto fail

:downloaded
rem The download must be exactly the file Adoptium published.
set "FTC_SUM="
for /f "skip=1 delims=" %%h in ('certutil.exe -hashfile "%FTC_ZIP%" SHA256 2^>nul') do if not defined FTC_SUM set "FTC_SUM=%%h"
if not defined FTC_SUM set "FTC_SUM=none"
set "FTC_SUM=%FTC_SUM: =%"
if /i "%FTC_SUM%"=="%FTC_SHA%" goto unpack
call :say "The download is damaged (its checksum doesn't match). Try again."
del "%FTC_ZIP%" >nul 2>nul
goto fail

:unpack
call :say "Unpacking Java..."
set "FTC_GOT="
if not exist "%FTC_SYS%\tar.exe" goto psunpack
"%FTC_SYS%\tar.exe" -xf "%FTC_ZIP%" -C "%FTC_DATA%\jdk" 1>&2
if not errorlevel 1 set "FTC_GOT=1"
if defined FTC_GOT goto unpacked

:psunpack
"%FTC_PS%" -NoProfile -NonInteractive -Command "$ProgressPreference='SilentlyContinue'; Add-Type -AssemblyName System.IO.Compression.FileSystem; [IO.Compression.ZipFile]::ExtractToDirectory($env:FTC_ZIP, (Join-Path $env:FTC_DATA 'jdk'))" 1>&2
if not errorlevel 1 set "FTC_GOT=1"

:unpacked
del "%FTC_ZIP%" >nul 2>nul
if not defined FTC_GOT goto nounpack
for /d %%d in ("%FTC_DATA%\jdk\jdk-21*") do call :consider "%%~fd"
if defined FTC_JDK goto found
call :say "Java was unpacked but isn't where it was expected, in %FTC_DATA%\jdk"
goto fail

:nofolder
call :say "Can't create the folder %FTC_DATA%\jdk"
goto fail

:nounpack
call :say "Java couldn't be unpacked into %FTC_DATA%\jdk"
goto fail

:newer
set "FTC_JDK=%FTC_JDK_NEWER%"

:found
set "JAVA_HOME=%FTC_JDK%"
call :cleanup
exit /b 0

:fail
call :cleanup
exit /b 1

:cleanup
set "FTC_JDK="
set "FTC_JDK_NEWER="
set "FTC_URL="
set "FTC_SHA="
set "FTC_ZIP="
set "FTC_GOT="
set "FTC_SUM="
set "FTC_MAJOR="
set "FTC_VERSION="
set "FTC_QUIET="
set "FTC_NO_DOWNLOAD="
set "FTC_DATA="
set "FTC_SYS="
set "FTC_PS="
exit /b 0

rem A folder is a usable JDK if it has the compiler (the app compiles team code) and says which
rem version it is in its "release" file: no program needs to be started to ask.
:consider
if defined FTC_JDK exit /b 0
if not exist "%~1\bin\javac.exe" exit /b 0
if not exist "%~1\bin\javaw.exe" exit /b 0
if not exist "%~1\release" exit /b 0
set "FTC_VERSION="
for /f "tokens=2 delims==" %%v in ('findstr /b /c:"JAVA_VERSION=" "%~1\release"') do set "FTC_VERSION=%%~v"
if not defined FTC_VERSION exit /b 0
set "FTC_MAJOR="
for /f "delims=." %%m in ("%FTC_VERSION%") do set "FTC_MAJOR=%%m"
if not defined FTC_MAJOR exit /b 0
if "%FTC_MAJOR%"=="21" set "FTC_JDK=%~1"
if "%FTC_MAJOR%"=="21" exit /b 0
if %FTC_MAJOR% GTR 21 if not defined FTC_JDK_NEWER set "FTC_JDK_NEWER=%~1"
exit /b 0

:say
if defined FTC_QUIET echo %~1 1>&2
if not defined FTC_QUIET echo %~1
exit /b 0

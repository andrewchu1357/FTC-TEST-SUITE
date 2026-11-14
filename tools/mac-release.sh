#!/bin/bash
# Builds the macOS release of FTC Test Suite and checks it. Run it on a Mac with Apple silicon,
# in the project folder (it needs a JDK 21, as `./gradlew run` does):
#
#   tools/mac-release.sh 1.0.0
#
# It makes viewer/build/dist/FTC-Test-Suite-macOS-<version>.dmg (`./gradlew macDmg`: the app with
# its own Java) and a .sha256 file next to it. Then it checks the DMG: it mounts it, checks the
# app's version, launcher and signature, and plays a headless match with the Java and the simulator
# inside the app, in a temporary copy of the app's starter files. Upload both files to the release.
# Nothing is installed, and nothing outside this folder and a temporary folder is changed.
set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
  echo "Usage: tools/mac-release.sh VERSION   (for example: tools/mac-release.sh 1.0.0)" >&2
  exit 2
fi
[ "$(uname -s)" = "Darwin" ] || { echo "This builds the Mac release: run it on a Mac." >&2; exit 2; }
[ "$(uname -m)" = "arm64" ] || { echo "The Mac release is for Apple silicon: build it on an M1 or later Mac." >&2; exit 2; }

cd "$(dirname "$0")/.."
echo "== Building the DMG, version $VERSION"
./gradlew --console=plain -PappVersion="$VERSION" macDmg

DIST="viewer/build/dist"
NAME="FTC-Test-Suite-macOS-$VERSION.dmg"
cp "$DIST/FTC Test Suite.dmg" "$DIST/$NAME"

echo "== Checking $NAME"
TMP="$(mktemp -d)"
MNT="$TMP/mnt"
mkdir "$MNT"
cleanup() {
  hdiutil detach "$MNT" -quiet >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT
hdiutil attach "$DIST/$NAME" -nobrowse -readonly -mountpoint "$MNT" -quiet
APP="$MNT/FTC Test Suite.app"
C="$APP/Contents"

[ -x "$C/MacOS/FTCTestSuite" ] || { echo "The app's launcher is missing or can't run." >&2; exit 1; }
[ -x "$C/runtime/bin/java" ] || { echo "The app's Java is missing." >&2; exit 1; }
SHOWN="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$C/Info.plist")"
[ "$SHOWN" = "$VERSION" ] || { echo "The app says it is version $SHOWN, not $VERSION." >&2; exit 1; }
echo "version: $SHOWN"
if codesign --verify --deep "$APP" 2>"$TMP/codesign.txt"; then
  echo "signature: ok (ad hoc: Gatekeeper asks once, see the release notes)"
else
  echo "WARNING: codesign --verify --deep says:"
  cat "$TMP/codesign.txt"
fi

# A headless match, the way the app runs its Java (the classpath in the app's own order).
WS="$TMP/workspace"
cp -R "$C/Resources/workspace" "$WS"
CP=""
while IFS= read -r jar; do
  if [ -n "$jar" ]; then CP="$CP${CP:+:}$C/app/lib/$jar"; fi
done < "$C/app/classpath.txt"
RESULT="$(cd "$WS" && "$C/runtime/bin/java" -javaagent:"$C/app/sim-clock-agent.jar" -Djava.awt.headless=true \
  -cp "$CP" org.ftctestbed.viewer.HeadlessMain --match --auto 'BuzzBot Auto Red' --teleop 'BuzzBot TeleOp' --start 'Red Wall' 2>&1)" || true
if ! echo "$RESULT" | grep -E '^(RED|BLUE) '; then
  echo "$RESULT" | tail -20 >&2
  echo "The headless match with the app's own Java didn't finish." >&2
  exit 1
fi

(cd "$DIST" && shasum -a 256 "$NAME" > "$NAME.sha256")
echo
echo "Done. For the GitHub release, upload:"
echo "  $DIST/$NAME ($(du -h "$DIST/$NAME" | cut -f1))"
echo "  $DIST/$NAME.sha256"

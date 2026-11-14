#!/usr/bin/env bash
# Builds the Linux portable package of CodeRun (launcher + game + its own Java), no installs needed by players.
#   Run from the project folder:   bash packaging/package-linux.sh
#   Result:                        dist/CodeRun-2.0-linux-x64.tar.gz
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${CODERUN_VERSION:-2.0.0}"

# ---- a JDK 21+ that has jpackage (JAVA_HOME, IntelliJ's ~/.jdks, or system)
find_jdk() {
  for d in "${JAVA_HOME:-}" "$HOME"/.jdks/* /usr/lib/jvm/* /opt/*jdk*; do
    [ -n "$d" ] && [ -x "$d/bin/jpackage" ] || continue
    v=$("$d/bin/java" -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/{print $2}')
    [ "${v%%.*}" -ge 21 ] 2>/dev/null && { echo "$d"; return; }
  done
}
JDK="$(find_jdk || true)"
[ -n "$JDK" ] || { echo "No JDK 21+ with jpackage found. Set JAVA_HOME to one (IntelliJ: File > Project Structure > SDKs)."; exit 1; }
export JAVA_HOME="$JDK"; export PATH="$JDK/bin:$PATH"
echo "Using JDK: $JDK"

# ---- checks
[ -x game_client/linux/CodeRun.x86_64 ] || [ -f game_client/linux/CodeRun.x86_64 ] || { echo "Missing game_client/linux/CodeRun.x86_64 (copy the Unity Linux build there)"; exit 1; }
[ -f packaging/config/db.properties ] || { echo "Missing packaging/config/db.properties (the limited player login)"; exit 1; }

# ---- compile the launcher and collect its libraries
chmod +x mvnw
./mvnw -q -DskipTests clean package
JAR=$(ls target/CodeRunLauncher-*.jar | grep -v sources | head -1)

rm -rf dist/input dist/CodeRun dist/CodeRun-2.0-linux-x64.tar.gz
mkdir -p dist/input/config
cp "$JAR" dist/input/
cp target/libs/*.jar dist/input/
cp packaging/config/*.properties dist/input/config/
mkdir -p dist/input/game_client
cp -a game_client/linux dist/input/game_client/linux
chmod +x dist/input/game_client/linux/CodeRun.x86_64
rm -rf dist/input/game_client/linux/*_BackUpThisFolder_ButDontShipItWithYourGame   # Unity debug files, not for players

# ---- the app folder with its own Java runtime
jpackage --type app-image \
  --name CodeRun \
  --app-version "$VERSION" \
  --vendor "CodeRun" \
  --input dist/input \
  --main-jar "$(basename "$JAR")" \
  --main-class com.example.coderunlauncher.Main \
  --icon packaging/icon.png \
  --java-options "-Dfile.encoding=UTF-8" \
  --dest dist

cp packaging/linux/install-shortcut.sh dist/CodeRun/
cp packaging/linux/README.txt dist/CodeRun/
chmod +x dist/CodeRun/install-shortcut.sh dist/CodeRun/bin/CodeRun

tar -C dist -czf dist/CodeRun-2.0-linux-x64.tar.gz CodeRun
rm -rf dist/input
echo
echo "Done: dist/CodeRun-2.0-linux-x64.tar.gz  ($(du -h dist/CodeRun-2.0-linux-x64.tar.gz | cut -f1))"
echo "Players: extract it, then run CodeRun/bin/CodeRun (or install-shortcut.sh once for a menu entry)."

#!/bin/bash
# Builds Solstice with plain javac against the Matsuri server's libraries/ (paper-api 26.2), Java 21
# bytecode. The jar is only written if TestMain passed.
#
# This is the maintainer's local fast path: it needs a Paper install's libraries/ folder (SERVER,
# defaulting to the maintainer's own server) and does not bundle bStats. ./gradlew build is the
# portable build and also compiles against every supported paper-api line.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
SERVER="${SERVER:-/home/thathunky/games/servers/matsuri}"
JDK="${JDK:-/usr/lib/jvm/temurin-25-jdk-amd64/bin}"
CP="$(find "$SERVER/libraries" -name '*.jar' | tr '\n' ':')"

rm -rf "$HERE/build/sh"
rm -f "$HERE/Solstice-1.0.0.jar"
mkdir -p "$HERE/build/sh/classes" "$HERE/build/sh/test"
"$JDK/javac" --release 21 -encoding UTF-8 -cp "$CP" -d "$HERE/build/sh/classes" \
    $(find "$HERE/src/main/java" -name '*.java')
"$JDK/javac" --release 21 -encoding UTF-8 -cp "$HERE/build/sh/classes:$CP" -d "$HERE/build/sh/test" \
    $(find "$HERE/src/test/java" -name '*.java')
(cd "$HERE" && "$JDK/java" -cp "$HERE/build/sh/classes:$HERE/build/sh/test:$HERE:$CP" dev.thathunky.solstice.TestMain)

cp "$HERE/plugin.yml" "$HERE/config.yml" "$HERE/build/sh/classes/"
cp -r "$HERE/lang" "$HERE/build/sh/classes/"
"$JDK/jar" --create --file "$HERE/Solstice-1.0.0.jar" -C "$HERE/build/sh/classes" .
echo "built: $HERE/Solstice-1.0.0.jar"

#!/usr/bin/env bash
# Boots Paper <version> headless with the Solstice jar, checks it enabled cleanly, runs
# "solstice status" and "season" on the console, and stops. Versions with world clocks (26.1+) are
# booted a second time to load the datapack the first start wrote.
#
#   ci/boot-test.sh 26.2 build/libs/Solstice-1.0.0.jar
#
# Meant for CI (see .github/workflows/compat.yml). Uses ./ci-run as the server folder.
set -euo pipefail

MC="$1"
JAR="$(realpath "$2")"
WORK="$(pwd)/ci-run"
rm -rf "$WORK"
mkdir -p "$WORK/plugins"

URL="$(curl -fsSL "https://fill.papermc.io/v3/projects/paper/versions/$MC/builds" \
  | jq -r '[.[] | select(.downloads["server:default"] != null)][0].downloads["server:default"].url')"
[ -n "$URL" ] && [ "$URL" != "null" ] || { echo "no Paper build for $MC"; exit 1; }
echo "Paper $MC: $URL"
curl -fsSL -o "$WORK/paper.jar" "$URL"
cp "$JAR" "$WORK/plugins/"

echo "eula=true" > "$WORK/eula.txt"
cat > "$WORK/server.properties" <<PROPS
level-type=minecraft\:flat
generate-structures=false
online-mode=false
server-port=25599
enable-rcon=false
enable-query=false
view-distance=3
simulation-distance=3
spawn-protection=0
max-players=2
PROPS

# Clock ticks by version: world clocks exist from 26.1 on.
major="${MC%%.*}"
has_clocks=0
[ "$major" -ge 26 ] && has_clocks=1

fail() { echo "FAIL ($MC): $*"; exit 1; }

# wait_for <log> <regex> <seconds> — returns 1 on timeout
wait_for() {
  local log="$1" pattern="$2" secs="$3" i=0
  while [ "$i" -lt "$secs" ]; do
    grep -Eq "$pattern" "$log" 2>/dev/null && return 0
    if [ -n "${PID:-}" ] && ! kill -0 "$PID" 2>/dev/null; then
      return 1
    fi
    sleep 1
    i=$((i + 1))
  done
  return 1
}

boot() {
  local label="$1"
  local log="$WORK/server-$label.log"
  rm -f "$WORK/console"
  mkfifo "$WORK/console"
  (cd "$WORK" && exec java -Xms256M -Xmx1536M -jar paper.jar --nogui < console > "$log" 2>&1) &
  PID=$!
  exec 3>"$WORK/console"

  wait_for "$log" 'Done \([0-9.,]+s\)!' 900 || { tail -n 80 "$log"; fail "server did not finish starting ($label)"; }
  echo "solstice status" >&3
  wait_for "$log" 'Calendar season:' 60 || { tail -n 40 "$log"; fail "no answer to solstice status ($label)"; }
  echo "season" >&3
  wait_for "$log" 'Season: ' 60 || { tail -n 40 "$log"; fail "no answer to season ($label)"; }
  sleep 8   # let the season clock task run once (it starts 5 s after enable)
  echo "stop" >&3
  exec 3>&-
  local i=0
  while kill -0 "$PID" 2>/dev/null && [ "$i" -lt 180 ]; do sleep 1; i=$((i + 1)); done
  kill -0 "$PID" 2>/dev/null && { kill -9 "$PID"; fail "server did not stop ($label)"; }
  PID=""

  grep -q '\[Solstice\] Enabling Solstice' "$log" || fail "Solstice was not enabled ($label)"
  if grep -Eq 'Error occurred while enabling Solstice|Could not load .*Solstice|at dev\.thathunky\.solstice\.' "$log"; then
    grep -nE -B2 -A12 'Error occurred while enabling Solstice|Could not load .*Solstice|at dev\.thathunky\.solstice\.' "$log" | head -n 80
    fail "exception from Solstice ($label)"
  fi
  if grep -Eq '<--\[HERE\]|Unknown or incomplete command' "$log"; then
    grep -nE -B2 '<--\[HERE\]|Unknown or incomplete command' "$log" | head -n 40
    fail "a console command sent by the plugin was rejected ($label)"
  fi
  sed -n '/\[Solstice\]/p' "$log"
}

boot first

if [ "$has_clocks" = 1 ]; then
  [ -f "$WORK/world/datapacks/solstice/data/solstice/timeline/year.json" ] || fail "the sky datapack was not written"
  grep -q 'restart the server to load it' "$WORK/server-first.log" || fail "no restart notice after writing the datapack"
  grep -q 'day-length=on' "$WORK/server-first.log" || fail "day length should be on with world clocks"
  boot second
  log="$WORK/server-second.log"
  if grep -Eiq 'Failed to (load|parse|reload) .*(registr|datapack|data pack)|Errors in registry|Failed to load registries' "$log"; then
    grep -Ein -A8 'Failed to (load|parse|reload)|Errors in registry|Failed to load registries' "$log" | head -n 60
    fail "the server rejected the sky datapack"
  fi
  grep -q 'sky=on' "$log" || fail "sky should be on after the restart"
else
  [ ! -d "$WORK/world/datapacks/solstice" ] || fail "a datapack was written on a version without world clocks"
  grep -q 'sky=off (needs Minecraft 26.1+' "$WORK/server-first.log" || fail "sky should be off with a reason"
  grep -q 'day-length=off (needs Minecraft 26.1+' "$WORK/server-first.log" || fail "day length should be off with a reason"
fi

if [ "$MC" = "1.21.4" ]; then
  grep -q 'particles-autumn=off' "$WORK/server-first.log" || fail "autumn leaves need 1.21.5+"
  grep -q 'particles-summer=off' "$WORK/server-first.log" || fail "fireflies need 1.21.5+"
fi

echo "OK: Paper $MC"

# Solstice

A Paper plugin that gives your world four seasons. Each season changes the sky tint and the length of
day and night, brings its own weather, puts particles under the trees, and changes how crops, animals
and fish behave. Winter is real: biomes turn snowy, rivers and ponds freeze, and in spring the natural
snow and ice melt while anything players placed stays.

Seasons follow either the real calendar (with a time zone and a northern or southern hemisphere) or a
fixed number of in-game days per season.

Solstice was built for a real Ukrainian-language survival server, where it ran as MatsuriSeasons, and
is now maintained here as its own project. The commands players already type (`/season`, `/pora`,
`/seasons`), the permission node admins already have, and the data it wrote into chunks and players
keep working (see [Coming from MatsuriSeasons](#coming-from-matsuriseasons)).

## What each season does

| | Winter | Spring | Summer | Autumn |
|---|---|---|---|---|
| Day / night speed | 0.972 / 0.321 (long nights) | 0.583 / 0.417 | 0.449 / 0.694 (long days) | 0.583 / 0.417 |
| Weather (clear / rain, minutes; thunder) | 10–20 / 10–30; never | 8–20 / 3–8; 10% | 20–45 / 3–6; 45% | 8–15 / 10–25; 10% |
| Particles | snowflakes from spruces | cherry petals | fireflies over grass at night | leaves under any natural tree |
| Crops on farmland | open sky: 60% of growth steps cancelled; greenhouses as usual | 25% chance of an extra stage | 25% chance of an extra stage | vanilla |
| Animals | 40% of breedings give no baby | babies grow up twice as fast | vanilla | vanilla |
| Fishing | vanilla | vanilla | vanilla | bites come a quarter faster |
| World | snowy biomes, snow and ice form and are recorded | recorded snow and ice melt, biomes return | | |

Every number above is a default in `config.yml`, and every feature has its own `enabled` switch.
Players see all of this with `/season`, and get a title, a chat line and a sound once per season.

Particles appear only where they belong: under natural leaves (or above grass and flowers at night)
and only under open sky. Never in caves, never under a roof. Each player sees only their own, so
crowds don't multiply them.

## Supported versions

Built as Java 21 bytecode against the oldest supported API, so one jar runs on every version below.
Anything version-specific is detected at startup; a feature the server can't do is switched off with
one log line and everything else keeps working.

| Feature | 1.21.4 | 1.21.5 – 1.21.10 | 1.21.11 | 26.1.x | 26.2 | 26.3 |
|---|---|---|---|---|---|---|
| Calendar (real-date, game-days), `/season`, titles | yes | yes | yes | yes | yes | yes |
| Weather by season | yes | yes | yes | yes | yes | yes |
| Crops, animals, fishing | yes | yes | yes | yes | yes | yes |
| Winter biomes, snow and ice that melt in spring | yes | yes | yes | yes | yes | yes |
| Spring petals, winter snowflakes | yes | yes | yes | yes | yes | yes |
| Autumn leaves, summer fireflies | — | yes | yes | yes | yes | yes |
| Day length by season | — | — | — | yes | yes | yes |
| Sky, fog, cloud and sunrise tint | — | — | — | yes | yes | yes |
| TAB animation, JSON file | yes | yes | yes | yes | yes | yes |

Why the gaps:

- **Autumn leaves and fireflies** use the `tinted_leaves` and `firefly` particles (and the firefly bush
  block), which arrived in 1.21.5. They are looked up in the registry by name, never by a constant.
- **Day length and sky colours** need world clocks, added in 26.1. 1.21.11 already has timelines, but
  a timeline there can only follow the day clock, not a year-long season clock. There is no API for
  clocks, so the plugin drives them with the vanilla `/time of <clock> …` command.

How this was checked:

- `./gradlew check` compiles the sources against paper-api 1.21.4, 1.21.8, 1.21.11, 26.1.2, 26.2 and
  26.3, and `compatLinkage` confirms the API members the bytecode references (owner, name, descriptor,
  class or interface) are identical across all six, so the 1.21.4-built jar links the same everywhere.
- `.github/workflows/compat.yml` boots every Paper version from 1.21.4 to 26.3 headless with the jar,
  checks it enables without exceptions, runs `solstice status` and `season`, and on 26.1+ boots a second
  time to confirm the server accepts the generated datapack.
- The sky datapack's formats were read from each vanilla server jar's `version.json` and built-in
  data: world clocks and the timeline format are the same in 26.1, 26.2 and 26.3. The pack declares
  `min_format` 101 (26.1) and `max_format` = the running server's format (101 on 26.1, 107 on 26.2,
  121 on 26.3), so a later game version never treats it as checked.
- The gameplay logic has run on a live Paper 26.2 server as MatsuriSeasons since September 2026 (winter has not come there yet). Solstice as a whole has so far been checked by the tests and builds above.

## Design constraint: no world scans

Solstice never walks the world looking for blocks to change. Everything it does is triggered by one of:

- **Random probes near online players.** Particles sample a couple of dozen random blocks around each
  player every half second — the idea of vanilla's random tick, scoped down. No chunk is scanned.
- **Chunks loading anyway.** Winter biomes are applied when a chunk loads, from a 4×4×4 biome
  snapshot (about 0.45 ms per chunk). When the season changes, the chunks that are already loaded are
  worked through ten per tick, with the time logged.
- **Game events.** Crop growth, breeding, fishing and snow/ice forming are decided in their own events.
- **Its own records.** Each chunk remembers its real biomes and every snow layer or ice block that
  formed during winter (up to 8192 per chunk). Spring puts back exactly those — nothing is searched for,
  and blocks players placed are never touched.

This was a deliberate choice for a server on a host with tight memory, where an unbounded scan can
stall the whole game. The cost: a chunk nobody loads during a season change catches up the next
time it loads, not before.

## Calendar

`calendar.mode` picks one of two:

- **`real-date`** (default) — meteorological seasons on the real calendar: winter is December to
  February in the north. `calendar.timezone` (e.g. `Europe/Kyiv`) decides when a day starts;
  `calendar.hemisphere: south` shifts everything by half a year, so June to August is winter. Progress
  within a season is measured between real instants, so a daylight-saving change never makes the sky
  jump.
- **`game-days`** — each season lasts `calendar.days-per-season` in-game days (default 8). The count is
  kept in `plugins/Solstice/state.yml` and survives restarts. A skipped night counts; `/time set` back
  or a jump of more than two days only moves the baseline, so commands can't rewind or fast-forward the
  seasons by accident. `/solstice set <season>` jumps on purpose.

Either way the season year is mapped onto one clock of 8,760,000 ticks (365 days × 24,000), which is
what the sky timeline follows.

## Sky colours (26.1+)

Solstice writes a small datapack into `<main world>/datapacks/<sky.datapack-folder>`: a world clock
(`<namespace>:season`), a timeline on that clock (`<namespace>:year`) with the tints from
`sky.colors`, and the tag that puts the timeline in the overworld. Clocks and timelines are registries
and only load at startup, so after the pack is first written (or its colours change) the plugin logs
that a restart is needed; until then the rest of the plugin runs and the clock is left alone. Once
loaded, the clock is paused and set by the plugin every 10 minutes (every minute in game-days mode).

Colours are written as signed ARGB numbers, not `"#rrggbb"` strings — a string there stopped a 26.2
server from loading its registries.

## Commands and permissions

| Command | Who | What |
|---|---|---|
| `/season` (alias `/pora`) | everyone (`solstice.season`, default true) | The season, until when, and what it changes |
| `/solstice status` (alias `/seasons`) | `solstice.admin` | Calendar season, preview, clock ticks, mode, Minecraft version, every feature and why it's off |
| `/solstice preview <season\|off>` | `solstice.admin` | Pretend it's that season (clock, weather, particles, winter) without touching the calendar |
| `/solstice set <season>` | `solstice.admin` | game-days mode: jump to the start of that season |
| `/solstice probe <x> <y> <z> <season> [night]` | `solstice.admin` | Where particles would appear near a point, without a player there |
| `/solstice chunk <x> <z>` | `solstice.admin` | A chunk's winter state (by block coordinates) |
| `/solstice reload` | `solstice.admin` | Re-read config.yml and the language files, restart every task |

`matsuri.seasons.admin` is a legacy node, kept for compatibility — same as `solstice.admin`.

## Configuration

See `config.yml` — every option is commented where it stands. In short: language, worlds, calendar
mode and its options, season colours, titles, day length per season, sky colours and datapack names,
particle density, weather windows, crop/animal/fishing numbers per season, winter limits and extra
biome pairs, and the two integrations.

Worlds: seasons act in the overworld-type worlds listed in `worlds`. The day length and sky follow the
server's main world (there is one overworld clock).

## Languages

Everything a player reads comes from `lang/<code>.yml`: en, uk, de, es, fr, pl, pt_BR, ja, zh_CN. Each
player gets their client's language when a file exists for it (`pt_PT` falls back to `pt_BR`, `zh_TW`
to `zh_CN`); otherwise `language` from config.yml, which defaults to English.

Season names are in the language files too, so a server can call them whatever it likes. A file you
put in `plugins/Solstice/lang/` overrides the bundled one key by key (keys it doesn't have still come
from the bundled copy), or adds a whole new language. The default language's file is copied there on
first start, ready to edit. Values are [MiniMessage](https://docs.advntr.dev/minimessage/format.html).

The Ukrainian file is the text players of the original server have been reading, word for word.

## Integrations

Both off by default. Paths are relative to the server folder.

- **TAB** (`integrations.tab`): writes an animation (default `season`) into TAB's `animations.yml` and
  runs `tab reload` when the season changes. Only the block between `# >>> season` and `# <<< season`
  is touched. Use it as `%animation:season%`.
- **JSON file** (`integrations.json-file`): writes
  `{"id": 3, "key": "autumn", "from": "2026-09-01", "to": "2026-11-30"}` when the season changes, for
  a website or another plugin. In game-days mode `from` and `to` are `null`.

Bedrock players (Geyser/Floodgate) are recognised through the Floodgate or Geyser API when installed,
for `particles.bedrock: false`.

## Coming from MatsuriSeasons

- Chunk data under `matsuri:winter`, `matsuri:biomes_palette`, `matsuri:biomes_orig` and
  `matsuri:formed` is read when a chunk loads and rewritten under `solstice:*`. The player key
  `matsuriseasons:season_seen` (and the older `ms_seen` scoreboard) is read the same way, so nobody sees
  the season title twice.
- The clock and timeline can keep their old names and folder: set `sky.namespace: matsuri` and
  `sky.datapack-folder: matsuri_seasons`. Solstice then takes over that folder; with the same colours its
  clock, timeline and tag come out byte-identical to the hand-made pack, so only `pack.mcmeta` is
  rewritten and no restart is asked for.
- An existing TAB block keeps its opening line.

## Building

### Gradle

```bash
./gradlew build
```

Produces `build/libs/Solstice-1.0.0.jar` (bStats bundled and relocated). `build` runs `TestMain`'s
server-free checks, compiles against every supported paper-api line and runs `compatLinkage`; any
failure fails the build. Needs a JDK 25 toolchain and network access to `repo.papermc.io` and Maven
Central.

### `build.sh` (maintainer's local fast path)

```bash
./build.sh
```

Compiles with plain `javac --release 21` against a Paper install's `libraries/` folder (`SERVER`,
defaulting to the maintainer's server, which runs 26.2), runs `TestMain`, and only writes
`Solstice-1.0.0.jar` if every check passes. It doesn't include bStats.

## bStats

The Gradle build shades [bStats](https://bstats.org) (`org.bstats` → `dev.thathunky.solstice.libs.bstats`)
with standard metrics only. The plugin id in
`src/bstats/java/dev/thathunky/solstice/stats/PluginMetrics.java` is a placeholder (`0`), which skips
metrics; register the plugin at bstats.org and put the real id there before a release. Server owners can
turn metrics off for all plugins in `plugins/bStats/config.yml`.

## License

MIT, see `LICENSE`. Copyright ThatHunky.

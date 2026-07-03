# War 'n Nobility (NeoForge 1.21.1)

A noble-rank and vassalage progression layer for the MineColonies ecosystem. You climb an
Outsider → Artisan → Knight → Baron → Count → Duke → King → Emperor ladder by collecting **seals**
from your peers; winning **War 'n Taxes** vassalization wars is how those seals are taken by force.
The mod also folds in a domain map (drawn on Antique Atlas) and a wall-mounted **War Table** planner.

- **Loader:** NeoForge 21.1.233, Minecraft 1.21.1, Java 21
- **Mod id:** `warnnobility`
- **License:** All Rights Reserved (see `LICENSE`)

## War 'n Taxes integration (the part Machiavelli cares about)

War 'n Nobility is an **add-on**: it never modifies War 'n Taxes. Every hook is either reflection or a
Mixin that lives entirely in this jar, and all of it is fail-soft (guarded by `ModList.isLoaded` /
`required = false`), so WnN runs standalone when War 'n Taxes is absent.

- **`integration/WarTaxesBridge`** — polls `net.machiavelli.minecolonytax.vassalization.VassalManager`
  (`isColonyVassal`, `getVassalOverlordUUID`, `getRemainingVassalizationHours`) and mirrors each WnT
  vassalization into a nobility bond.
- **Seal → vassal model:** a WnT win makes the loser a *provisional seal*, not a vassal. Same-rank →
  provisional (converts when the winner ascends); a higher noble beating a lower one → an immediate,
  sentenced vassal; a lower noble can't bind their better. The WnT sentence timer carries over on
  ascension.
- **`mixin/VassalsPageMixin`** — wraps War 'n Taxes' own `VassalsPage` vassal supplier to tag
  provisional rows `(Provisional)` in the tax book. WnT draws it; WnT code is untouched.
- **`mixin/WarSystemMixin`** — cancels `WarSystem.processWageWarRequest(...)` when the target colony's
  owner outranks the declarer ("no war upon your betters"). Toggle: `protect_higher_rank_from_war`.
- **`integration/ColonyHostility`** — on `/renounce`, sets the rebel HOSTILE in the ex-liege's colonies
  via MineColonies permissions.

## Building

Requires a **Java 21** JDK. The proprietary dependency jars are **not** in this repo — supply them
yourself and point Gradle at the folder that holds them.

1. Put these jars (or your own equivalents) in one folder — e.g. any MineColonies instance's `mods/`:
   - `minecolonies-1.1.1319-1.21.1.jar`
   - `structurize-1.0.830-1.21.1.jar`
   - `blockui-1.0.211-1.21.1-snapshot.jar`
   - `domum-ornamentum-1.0.234-snapshot-main.jar`
   - `WarNTaxes-1.21.1-NeoForge-5.0.jar`
   - `easy_factions-NeoForge-1.0.3.jar`
2. Tell Gradle where they are (pick one):
   - add `deps_dir=C:/path/to/that/mods/folder` to your `~/.gradle/gradle.properties`, **or**
   - pass it per build: `./gradlew build -Pdeps_dir=C:/path/to/mods`
3. Point Gradle at a Java 21 JDK (pick one): `org.gradle.java.home=...` in `~/.gradle/gradle.properties`,
   or set `JAVA_HOME`, or let the toolchain resolve one.
4. Build:
   ```
   ./gradlew build
   ```
   The mod jar lands in `build/libs/warnnobility-neoforge-<version>.jar`.

All six dependencies are `compileOnly` (optional at runtime); WnN links against their APIs but each
integration only activates when that mod is actually installed.

## Source map

| Package | What lives there |
|---|---|
| `warnnobility` | mod entry (`WarNNobility`), `Config` |
| `nobility` | ranks, seals, vassalage state (`NobilityManager`, `NobleData`, `WarRankGuard`) |
| `command` | `/nobility …` (op) and `/renounce` (player) |
| `integration` | War 'n Taxes bridge, Easy Factions gate, colony hostility |
| `domain` | county/duchy/kingdom territory + Antique Atlas overlay |
| `warmap` | the multiblock War Table planner + HYW command layer |
| `mixin` | fail-soft overlays on War 'n Taxes / HYW GUIs (never their source) |
| `net` | client/server payloads |

## Notes for contributors

- Nothing here edits another mod's source. Keep integrations reflection- or mixin-based and fail-soft.
- Dependency jars, `build/`, `.gradle/`, and the dev `run/` folder are gitignored — don't commit them.
- Machine-specific settings (JDK path, `deps_dir`) belong in your `~/.gradle/gradle.properties`, not in
  the committed `gradle.properties`.

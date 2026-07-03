# War 'n Nobility

A standalone MinecraftForge 1.21.1 mod (Java 21) that adds a configurable noble-rank ladder,
vassalage, buy-ins, and same-rank "Casus Belli" wars. Built to later hook into MineColonies
**War 'n Taxes** once that mod updates to 1.21.1; until then it runs entirely on its own.

- **Mod id:** `warnnobility`
- **Loader:** Forge 1.21.1 (52.x), Java 21 (same toolchain as POIs and Raid Bosses)

## The ladder (final, per owner's ruling)

`Outsider -> Artisan -> Knight -> Baron -> Duke -> Grand Duke -> King -> Emperor`

The original brief contradicted itself (it listed a Count and had two different paths to Duke).
The owner's final call removed Count and made Baron's exit a buy-in. Each step's gate:

| Step | How you advance |
| --- | --- |
| Outsider -> Artisan | `/nobility promote` (free; hook a quest here) |
| Artisan -> Knight | `/nobility promote` (free) |
| Knight -> Baron | 4 Knights pledge `/nobility support`, then `/nobility promote` |
| Baron -> Duke | **buy-in**: pay the configured item/amount (default 64 Emerald), then `/nobility promote` |
| Duke -> Grand Duke | reach score 3 (won via wars), then `/nobility promote` |
| Grand Duke -> King | reach score 3, then `/nobility promote` |
| King -> Emperor | automatic: the highest-scoring King wears the crown |

Nothing above is hard-coded. The ladder is a config list of `name;REQUIREMENT;param;entryScore`
lines, so owners can rename titles, add or remove ranks, change the buy-in, or move the war
bracket. The mechanics follow the REQUIREMENT column, not the title text.

## Scoreboard and wars

- Score is a single integer per noble.
- Only same-rank nobles at or above the war bracket (default Duke) may declare a Casus Belli.
- `war declare` records the war; `war resolve` settles it: winner +1, loser -1 (floored at 0).
- Ascending into a new bracket resets your score to that rank's `entryScore` (1 for Duke/GrandDuke/King).
- The Emperor is dynamic: after any score change the highest-scoring King is crowned, and the
  throne only shifts when a rival STRICTLY surpasses the sitting Emperor, so dethroning takes
  several consecutive wins. The Emperor's score is never reset, so it can grow without limit.

## Commands (ops / quests / command blocks, permission level 2)

- `/nobility promote <player>` — advance if the next step's condition is met
- `/nobility demote <player>` — drop one rank (admin)
- `/nobility setscore <player> <value>` — set score (admin); also re-checks the Emperor throne
- `/nobility support <supporter> <target>` — pledge a Knight's support toward a Baron bid
- `/nobility war declare <challenger> <target>` — open a same-bracket Casus Belli
- `/nobility war resolve <winner> <loser>` — settle a declared war, moving a point
- `/nobility info [player]` — print a player's title, score, liege, vassals, and next step

## The Chancery Table

A block where a noble's clerks draft decrees. It looks like a crafting table (placeholder art via
a blockstate pointing at the vanilla model) but is named **Chancery Table**, and on right-click it
opens the player's interactive console. This is the seat of power: every command a noble can run
is a button here.

- **Standing**, rank-filtered: only the lines that matter at your rank show. An Outsider has no
  Vassals line, a sub-war-tier rank has no Score, a rank past the support stage has no Supporters.
  The visible set auto-derives from the ladder and is overridable per rank via the config `show`
  field (`score,supporters,vassals,liege`).
- **Advance** button: runs the next promotion, including paying a buy-in (label shows the cost).
- **Declare War** buttons: one per same-rank online rival (war tier only).
- **Support** buttons: one per same-rank peer seeking backers (Knights backing a Baron claim).
- Each press runs the identical server-side logic as the matching `/nobility` command, reports the
  result in chat, then refreshes the console. Commands still work too; the table is just the GUI.

The "how to advance" line uses the per-rank `advanceText` config when set, so a questline server
can replace "reach score 3" with "Complete the Ducal trials." Obtain the block from the Functional
Blocks creative tab or `/give <you> warnnobility:chancery_table`.

## War 'n Taxes integration (the seam)

`integration/TaxBridge` is the single, documented hook. It reports inactive while War 'n Taxes
is absent (it is not on 1.21.1 yet), so tribute is dormant and nothing is faked. When the mod
ships for 1.21.1, that bridge reads rank/liege from `NobilityManager` and routes tribute and
per-rank tax scaling through War 'n Taxes' own economy. The mods.toml has the soft dependency
ready to uncomment.

## Deliberately deferred to a later version

- A graphical **War Table** map of houses/territories. v1 ships the chat `/nobility info` panel
  and the Chancery console instead; the full map is a bigger art/UI job.
- Offline-player targeting: commands use online players for now (clean names, no profile lookups).
- Real tribute money flow: needs War 'n Taxes' economy, hence the dormant bridge above.

## Build

`./gradlew build` -> `build/libs/warnnobility-1.0.0.jar`. First build reuses the cached 1.21.1
decompile from the POIs project. `./gradlew runClient` for a dev client.

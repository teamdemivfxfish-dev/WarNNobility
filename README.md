# MineColonies: War 'n Nobility

> A configurable noble-rank and vassalage progression layer for MineColonies. Minecraft 1.21.1 · NeoForge (Forge variant available) · modid `warnnobility` · v1.0.6 · by Barunn (NewtL)

**[Download on CurseForge](https://www.curseforge.com/minecraft/mc-mods/war-n-nobility)**

War 'n Nobility turns a MineColonies server into a feudal ladder. Players climb an eight-rung title
ladder, from Outsider to Emperor, by collecting **seals** of fealty from their peers. Voluntary seals
are pledged in the Chancery; forced seals are taken by winning **War 'n Taxes** vassalization wars, so
the wars a rival system actually fights are what promote you. Your growing county, duchy, or realm is
drawn on the Antique Atlas map, decrees are issued from a Chancery Table block, and campaigns are
planned on a wall-mounted War Table.

The mod never edits another mod's code: every integration is reflection or a fail-soft Mixin that lives
entirely in this jar, so the nobility ladder still runs when the optional partners are absent.

> [!NOTE]
> Nothing about the ladder is hard-coded. The ranks, their requirements, the vassal sentence, and the
> King gate are all config; the mechanics follow the requirement column, not the title text, so you can
> rename titles or reshape the whole ladder without touching code.

## Supported Versions

| Loader | Minecraft | Mod version | Folder |
|---|---|---|---|
| NeoForge | 1.21.1 | 1.0.6 | `/` (primary) |
| Forge | 1.21.1 | 1.0.0 | Forge variant (`WarNNobility`) |

## Crafting & Items

**Chancery Table** — the noble's console block.
```
 G
G L G
 B
```
G = Gold Ingot · L = Lectern · B = Writable Book

**War Frame** — one panel of the War Table; makes six.
```
S S S
S P S
S S S
```
S = Stick · P = Paper

**Crown** — the regalia consumed when a Duke claims the throne.
```
G S G
G G G
```
G = Gold Ingot · S = Shard from the Crown of Unification

The **Shard from the Crown of Unification** has no recipe on purpose. It is meant to be admin-granted or
dropped by an out-of-band event (for example the POIs & Raid Bosses mod), so reaching King always needs
more than crafting. Blocks appear in the Functional Blocks creative tab; the Shard and Crown appear in
Ingredients.

| Item | Display name | Notes |
|---|---|---|
| `warnnobility:chancery_table` | Chancery Table | Right-click to open your noble console |
| `warnnobility:war_frame` | War Frame | Item-frame-style panel; 3x3 + an atlas = a War Table |
| `warnnobility:crown_shard` | Shard from the Crown of Unification | No recipe; admin- or event-granted |
| `warnnobility:crown` | Crown | Crafted from a Shard; the King gate |

## Controls

This mod registers no keybinds of its own. It is driven by block interactions, and its RTS command
layer reads Hundred Years War's own keys so the on-screen HUD always teaches your actual bindings.

| Action | How |
|---|---|
| Open your noble console | Right-click a **Chancery Table** |
| Mount a War Frame | Use an atlas on a completed, like-facing 3x3 War Frame grid |
| Open the War Table planner | Right-click the War Table anchor frame |
| Rotate a flat War Table | Shift + right-click the table |
| View domain borders | Open the Antique Atlas map |

## Mechanics

### The nobility ladder

The default ladder, lowest first, is `Outsider -> Artisan -> Knight -> Baron -> Count -> Duke -> King ->
Emperor`. Each rank declares how you *enter* it:

| Requirement | How you advance into the rank |
|---|---|
| `COMMAND` | Just `/nobility promote` (hook quests, NPCs, or boss kills here). The early ranks use this |
| `SUPPORT` | A number of same-rank peers must pledge their seal to you; on promotion those sealers become your sentenced vassals |
| `BUYIN` | Pay a configured item, consumed on promotion (unused by the default ladder) |
| `SCORE` | Your score must have reached a threshold (unused by the default ladder) |
| `CROWN` | The King step: collect Duke seals, lead an Easy Factions faction over the claim threshold, and consume a Crown |
| `HIGHEST` | The Emperor seat. The first King is crowned automatically; after that the throne changes only by usurpation |

In the default ladder Count needs 2 Baron seals, Duke needs 2 Count seals, and King needs 2 Duke seals.
Each rank also carries a feminine title (Dame, Baroness, Countess, Duchess, Queen, Empress) that a player
can switch to in the Chancery or with `/nobility gender`.

### Seals and vassalage

A **seal** is a pledge of fealty. A peer pledges voluntarily with `/nobility seal` (revocable with
`/nobility unseal`), or is forced to by a lost war (see below). When a claimant with a `SUPPORT` or
`CROWN` requirement promotes, the peers who sealed them become their **vassals**, bound for a sentence
of real-world days (`vassal_sentence_days`, default 7) so players cannot vassal-hop to swing different
lords' promotions. A bound player may `/renounce` only after the sentence is served.

### War 'n Taxes drives the seals

When War 'n Taxes is installed, `WarTaxesBridge` polls its `VassalManager` and mirrors each colony
vassalization into a nobility bond, so **winning War 'n Taxes wars is how you collect the same-rank seals
that promote you**. A win does not immediately make the loser a vassal; it creates a *provisional seal*
that converts to a sworn vassal when the winner next ascends, and the War 'n Taxes sentence timer carries
over. A higher noble beating a lower one binds them immediately; a lower noble cannot bind their better.
`VassalsPageMixin` tags each still-provisional colony **(Provisional)** in War 'n Taxes' own tax-book
vassal page, drawn by War 'n Taxes with no change to its code.

### Feudal war protection

With `protect_higher_rank_from_war` on, `WarSystemMixin` cancels a War 'n Taxes war declared on a colony
whose owner outranks the declarer ("you may not make war upon your betters"). The attacker is told why
and the declaration is suppressed; equal or lower rank is unaffected.

### Renouncing a liege

A vassal past their sentence may `/renounce`. They keep their own title, but the break is open defiance:
`ColonyHostility` marks the rebel HOSTILE in every colony the ex-liege owns, so MineColonies guards turn
on them until the quarrel is settled.

### The Emperor throne

Emperor is the `HIGHEST` seat. The first King to qualify is crowned automatically. After that the throne
changes only through a usurpation challenge, fed in by external war or siege outcomes through
`/nobility emperor ...`, so a challenger must actually take the sitting Emperor's imperial duchies to
seize the crown.

### Domains and the map

A Count and up commands a named domain, a **county**, then a **duchy**, then a **realm**, set with
`/nobility domain`. When Antique Atlas 4 is installed, `DomainOverlay` draws each domain's borders and
name labels straight onto the atlas screen through NeoForge screen events and reflection, with a
plain-parchment fallback when the atlas is absent.

### The Chancery Table

The Chancery Table is a noble's console. Right-click opens a rank-filtered panel where every action a
noble can take, advance, seal, declare, name a domain, is a button that runs the same server-side logic
as the matching command and then refreshes. Only the lines that matter at your rank are shown, derived
from the ladder and overridable per rank in config.

### The War Table

Six War Frames craft at once. Mounted like item frames, nine like-facing frames form a 3x3 grid;
inserting an atlas activates it as a live, drawable briefing map with a shared view, a marker lock, and a
persistent plan. A completed grid resists mining and takes three hits to dismantle so nobody wipes a
briefing mid-sentence. When Hundred Years War is present the board gains a Company-of-Heroes-style command
layer over HYW's army units and freecam commander mode; it stays dormant otherwise.

## Configuration

File: `config/warnnobility-common.toml`.

**`[ranks]`**

| Key | Default | What it does |
|---|---|---|
| `ladder` | the 8-rank list below | The nobility ladder, lowest first. Each line is `name;REQUIREMENT;param;entryScore;show;advanceText;femaleName`; only the first four are required |

Default ladder lines: `Outsider;COMMAND;0;0`, `Artisan;COMMAND;0;0`, `Knight;COMMAND;0;0`,
`Baron;COMMAND;0;0`, `Count;SUPPORT;2;0`, `Duke;SUPPORT;2;0`, `King;CROWN;2;0`, `Emperor;HIGHEST;0;-1`
(each also carries a feminine title). `entryScore` is the score you are set to on entering a rank; a
negative value leaves the score alone. `show` chooses which Chancery lines (`score,supporters,vassals,
liege`) appear at that rank; `advanceText` is custom "how to advance" text for a questline.

**`[vassalage]`**

| Key | Default | What it does |
|---|---|---|
| `vassal_sentence_days` | `7` | Real-world days a vassal stays bound before it may break free (0 to 3650). Conquered vassals serve the same term once the conqueror ascends |

**`[economy]`**

| Key | Default | What it does |
|---|---|---|
| `buyin_item` | `minecraft:emerald` | Item consumed for a `BUYIN` promotion. Unused by the default ladder |

**`[integration]`**

| Key | Default | What it does |
|---|---|---|
| `tribute_enabled` | `true` | Bridge War 'n Taxes vassalization wars into seals. Harmless to leave true when War 'n Taxes or MineColonies is absent |
| `tribute_poll_seconds` | `20` | How often (seconds) to poll War 'n Taxes for new or ended vassalizations (1 to 600) |
| `protect_higher_rank_from_war` | `true` | Forbid declaring a War 'n Taxes war on a colony whose owner outranks the declarer. False lets anyone declare on anyone |
| `king_requires_faction` | `true` | King gate: the claimant must lead an Easy Factions faction. Skipped if Easy Factions is absent |
| `king_faction_claim_threshold` | `50` | King gate: total claimed chunks the claimant's faction must hold (0 to 1,000,000) |
| `king_consume_crown` | `true` | King gate: consume the Crown at coronation. False = the King only has to bear one |

## Compatibility

Integrations are reflection- or Mixin-based and fail-soft; the ladder runs standalone. Per
`neoforge.mods.toml` the mod declares:

- **MineColonies** (required) — the colony system read for territory and the wars that drive seals.
- **War 'n Taxes** (required; its modid is `minecolonytax`, not `warntaxes`) — runs the vassalization
  wars whose outcomes become seals, and provides the tax book that gets the (Provisional) tag.
- **Antique Atlas 4** (optional, client) — the domain-border map overlay; falls back to plain parchment.
- **Easy Factions** (optional) — backs the King gate and the faction map overlay; the King gate stays
  dormant when it is absent.
- **Hundred Years War** (optional) — supplies the army units and freecam commander mode the War Table's
  command layer overlays; the War Table just shows no field units when it is absent.

## Commands

All `/nobility ...` subcommands require permission level 2 (ops, command blocks, quest rewards).
`/renounce` is player-facing.

- `/nobility promote|demote <player>` — advance or drop a rank
- `/nobility setrank <player> <rank>` — jump a player to any rank, ignoring requirements
- `/nobility setscore <player> <value>` — set a player's score
- `/nobility seal <supporter> <target>` / `/nobility unseal <supporter>` — pledge or revoke a voluntary seal
- `/nobility subjugate <aggressor> <defender>` — force a seal, the outcome of a vassalization war
- `/nobility vassal release <liege> <vassal>` / `/nobility vassal leave <vassal>` — end a bond
- `/nobility emperor throne|war|usurp|status <challenger> [...]` — feed throne and usurpation outcomes
- `/nobility gender <player> male|female` — switch a player's title form
- `/nobility domain <player> <name|clear>` — name or clear a domain (Count and up)
- `/nobility directive <rank> <text|clear>` — set custom "how to advance" text for a rank
- `/nobility info [player]` — print a player's title, domain, liege, vassals, seals, and next step
- `/renounce` — break from your liege once your sentence is served (turns the ex-liege's colonies hostile)

## Building

```
./gradlew build
```

Requires a **JDK 21**. The proprietary dependency jars (MineColonies, War 'n Taxes, Easy Factions and
their libraries) are **not** committed to this repo; they are `compileOnly`, so supply them yourself and
point Gradle at the folder that holds them.

1. Put the dependency jars in one folder, for example a MineColonies instance's `mods/`:
   `minecolonies-1.1.1319-1.21.1.jar`, `structurize-1.0.830-1.21.1.jar`,
   `blockui-1.0.211-1.21.1-snapshot.jar`, `domum-ornamentum-1.0.234-snapshot-main.jar`,
   `WarNTaxes-1.21.1-NeoForge-5.0.jar`, `easy_factions-NeoForge-1.0.3.jar`.
2. Point Gradle at that folder: add `deps_dir=C:/path/to/mods` to `~/.gradle/gradle.properties`, or pass
   `-Pdeps_dir=C:/path/to/mods` on the command line.
3. Point Gradle at a Java 21 JDK: `org.gradle.java.home=...` in `~/.gradle/gradle.properties`, or set
   `JAVA_HOME`, or let the toolchain resolve one.

The jar lands in `build/libs/warnnobility-neoforge-1.0.6.jar`.

## License

MIT — see [LICENSE](LICENSE).

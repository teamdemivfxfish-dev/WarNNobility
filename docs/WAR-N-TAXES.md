# War 'n Nobility ↔ War 'n Taxes: the seam

For the War 'n Taxes side. This is everything War 'n Nobility touches in War 'n Taxes, what it relies on
staying true, and what it already gives back, so the integration can be worked on from either end without
either mod finding out the hard way.

Short version: **War 'n Taxes owns the wars and the colony vassalizations. War 'n Nobility owns the noble
ladder and the map.** The link runs one way today, from Nobility into Taxes, and it is a poll.

## What War 'n Nobility calls

All of it lives in one class, `com.newtl.warnnobility.integration.WarTaxesBridge`. Nothing else in the mod
touches a War 'n Taxes type, and every reference sits behind a `ModList.get().isLoaded("minecolonytax")`
guard, so the class still links when the jar is absent.

The mod id is **`minecolonytax`**, not `warntaxes`. The display name is the misleading one.

Three static methods on `net.machiavelli.minecolonytax.vassalization.VassalManager`:

| Call | Used for |
|---|---|
| `isColonyVassal(int colonyId)` | is this colony currently a vassal at all |
| `getVassalOverlordUUID(int colonyId)` | who won it, as a player UUID |
| `getRemainingVassalizationHours(int colonyId)` | how long the bond has left to run |

That is the entire surface. If those three keep their names, signatures and meanings, nothing here breaks.

## What it does with them

Every `tributePollSeconds` (config, default 30) the bridge walks `IColonyManager.getAllColonies()` and asks
those three questions per colony. There is no list-all-vassals call in War 'n Taxes, which is the only
reason this is a scan rather than a lookup.

- A colony that has **become** a vassal is mirrored into a forced seal on the ladder
  (`NobilityManager.subjugate(overlord, vassalOwner)`), which is how winning a vassalization war in Taxes
  is what promotes a player to Count, Duke or King in Nobility.
- The seal's "cannot break free yet" window is pinned to `getRemainingVassalizationHours`, so the two mods
  cannot disagree about how long a vassal is bound.
- A colony that has **stopped** being a vassal releases the bond (`releaseWnTBond`) and is forgotten, so a
  later re-conquest fires fresh.
- Vassalizations that cannot map onto a legal ladder step (a lord and a vassal who are not peers) are
  recorded as rejected and skipped quietly, not retried and not logged every poll.
- Any throwable out of the three calls disables the bridge for the session and logs once. Nobility keeps
  working standalone; it does not take the server down over a moved method.

## What you already get back, without doing anything

**A War 'n Taxes vassal already renders inside its liege's domain on the map.** `DomainEngine` asks
`WarTaxesBridge.overlordOf(colonyId)` when it builds the territory outlines, so a Duke who vassalized a
Count sees one combined Duchy in Antique Atlas rather than two separate blobs. That works off the same
three calls above and needs nothing new from the Taxes side.

That was the "hook vassalage to the WnN domain system" note. It is done; it just is not documented anywhere
you would have seen it.

The tax book's **"(Provisional)"** tags on vassal rows are also fed from here: each poll pushes every online
player the colony ids whose seals are still provisional.

## What would make this better, from your side

None of it is required. In rough order of value:

1. **A list-all-vassals accessor**, something like `Map<Integer, UUID> getActiveVassals()` or even
   `Set<Integer> getVassalColonyIds()`. That turns the whole poll from "walk every colony on the server
   every 30 seconds" into one map read, and the scan is the only part of this integration with a cost that
   grows with the size of the server.
2. **An event when a vassalization starts or ends** (colony id, overlord UUID, duration). With that the poll
   disappears entirely and a seal lands the moment the war resolves rather than up to 30 seconds later.
3. **Keep `getVassalOverlordUUID` public.** It is the one call with a history: War 'n Taxes 4.0.1 did not
   have it, so the Forge 1.20.1 backport of this mod reads `VassalManager.ACTIVE_VASSALS` reflectively to
   get at `VassalRelation.overlordUUID` instead. That fallback works but it is exactly the kind of thing
   that breaks silently on a refactor.

## If you want to call into Nobility

The ladder is a `SavedData`: `NobilityManager.get(server)`, then

- `subjugate(UUID overlord, UUID vassal)` — force a seal, returns a `Result(success, message)`
- `releaseWnTBond(UUID overlord, UUID vassal)` — undo one
- `setWnTSentence(UUID overlord, UUID vassal, long untilEpochMillis)` — set the unbreakable window
- `isProvisionalSeal(UUID overlord, UUID vassal)` — whether it still counts as provisional
- `peek(UUID)` / `getOrCreate(UUID)` — a player's `NobleData` (rank index, score, liege, seals)

`DomainEngine.recompute(server)` rebuilds the map outlines and returns how many domains it drew.

Rank rules are data, not code: the ladder is a config list, so a server can rename ranks or change the
requirements without either mod being rebuilt.

## Versions

- This mod: MC **1.21.1**, NeoForge **21.1.233**, Java 21, built against War 'n Taxes **5.0**.
- There is a Forge **1.20.1** backport of the core (no War Table / RTS layer) that targets War 'n Taxes
  **4.0.1**, with the reflective overlord lookup described above. It is not in this repo.

## Building it

The proprietary dependency jars are deliberately not committed. Put MineColonies, Structurize, BlockUI,
Domum Ornamentum, War 'n Taxes and Easy Factions in one folder, point `deps_dir` at it in
`~/.gradle/gradle.properties`, and `./gradlew build`. Full detail in the [README](../README.md#building).

package com.newtl.warnnobility.nobility;

import com.newtl.warnnobility.Config;
import com.newtl.warnnobility.WarNNobility;
import com.newtl.warnnobility.integration.FactionGate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The whole nobility PROGRESSION machine, persisted to world save data
 * ({@code <world>/data/warnnobility.dat}) so it is global to the save.
 *
 * <p>This mod owns titles, seals, fealty and the throne; it does NOT own war. A vassalization or
 * usurpation war is fought elsewhere (an admin/quest now, Machiavelli's War 'n Taxes later) and its
 * OUTCOME is fed in here: {@link #subjugate} takes a defender's seal by force, and the Emperor
 * usurpation conditions are flipped by {@link #emperorThrone}/{@link #emperorWar}. Everything that
 * is a pure relationship (who vassalized whom) the mod tracks and checks itself.
 *
 * <p>The ladder is built from {@link Config}, so renaming or retuning ranks in the toml takes
 * effect on the next server start.
 */
public class NobilityManager extends SavedData {

    private static final String DATA_NAME = "warnnobility";

    private final Map<UUID, NobleData> nobles = new LinkedHashMap<>();
    private final Map<UUID, EmperorBid> challenges = new HashMap<>();
    /** Admin-set "how to advance from here" text, keyed by lowercase rank name; overrides config. */
    private final Map<String, String> customDirective = new HashMap<>();
    private boolean emperorEverCrowned = false;
    private final List<Rank> ladder;

    /** Progress of one King's bid for the throne. Capture-of-duchies is derived, not stored. */
    public static final class EmperorBid {
        public boolean throne;   // holds King's Landing (set by an external siege/war outcome)
        public boolean warWon;   // won the war on the Emperor (set by an external war outcome)
    }

    public record Result(boolean success, String message) {
        public static Result ok(String m)   { return new Result(true, m); }
        public static Result fail(String m)  { return new Result(false, m); }
    }

    public NobilityManager() {
        this.ladder = buildLadder();
    }

    private static List<Rank> buildLadder() {
        List<Rank> out = new ArrayList<>();
        for (String s : Config.RANKS.get()) out.add(Rank.parse(s));
        if (out.isEmpty()) out.add(new Rank("Commoner", RequirementType.COMMAND, 0, 0, null, "", ""));
        return out;
    }

    // ---- access -------------------------------------------------------------

    public static NobilityManager get(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        return overworld.getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(NobilityManager::new, NobilityManager::load, null), DATA_NAME);
    }

    public List<Rank> ladder() { return ladder; }

    public NobleData getOrCreate(UUID id) {
        return nobles.computeIfAbsent(id, NobleData::new);
    }

    public NobleData getOrCreate(ServerPlayer player) {
        NobleData d = getOrCreate(player.getUUID());
        d.name = player.getGameProfile().getName();
        return d;
    }

    @Nullable
    public NobleData peek(UUID id) { return nobles.get(id); }

    /** The holder's title, in its feminine form if they identify as a woman. */
    public String rankName(NobleData d) {
        return ladder.get(clamp(d.rankIndex)).displayName(d.female);
    }

    /** The holder's title colour (packed 0xRRGGBB) for the chat + tab name prefix. */
    public int rankColor(NobleData d) {
        return Rank.colorFor(ladder.get(clamp(d.rankIndex)).name());
    }

    public String nameOf(@Nullable UUID id) {
        if (id == null) return "None";
        NobleData d = nobles.get(id);
        return d != null ? d.name : "Unknown";
    }

    private long sentenceMillis() {
        return Config.VASSAL_SENTENCE_DAYS.get() * 86_400_000L;
    }

    // ---- ladder helpers -----------------------------------------------------

    private int clamp(int i) {
        return Math.max(0, Math.min(i, ladder.size() - 1));
    }

    private int emperorIndex() {
        for (int i = 0; i < ladder.size(); i++) {
            if (ladder.get(i).requirement() == RequirementType.HIGHEST) return i;
        }
        return ladder.size() - 1;
    }

    private int kingIndex()  { return Math.max(0, emperorIndex() - 1); }
    private int dukeIndex()  { return Math.max(0, emperorIndex() - 2); }

    /** Index of the first rank ENTERED via SUPPORT (the lowest rank that holds vassals: the Count). */
    private int firstSupportEntryIndex() {
        for (int i = 0; i < ladder.size(); i++) {
            if (ladder.get(i).requirement() == RequirementType.SUPPORT) return i;
        }
        return Integer.MAX_VALUE;
    }

    @Nullable
    public Rank nextRank(int rankIndex) {
        int i = clamp(rankIndex) + 1;
        return i < ladder.size() ? ladder.get(i) : null;
    }

    /** Is a noble at this rank currently gathering seals for their next step? */
    public boolean seeksSupport(int rankIndex) {
        Rank next = nextRank(rankIndex);
        return next != null && (next.requirement() == RequirementType.SUPPORT
                || next.requirement() == RequirementType.CROWN);
    }

    /** May the player ADVANCE themselves from the Chancery (vs admin/NPC-only COMMAND/HIGHEST steps)? */
    public boolean playerMayAdvance(NobleData d) {
        Rank next = nextRank(d.rankIndex);
        if (next == null) return false;
        return switch (next.requirement()) {
            case SUPPORT, BUYIN, SCORE, CROWN -> true;   // earned/claimed by the player
            default -> false;                            // COMMAND/HIGHEST handled elsewhere
        };
    }

    /**
     * Which console lines show at a rank. Honors an explicit config override, otherwise derives a
     * sensible set: seals only while gathering them, vassals once a rank can hold them, liege
     * whenever one exists. Score is opt-in (no war here to earn it).
     */
    public Set<Element> visibleFor(int rankIndex) {
        int i = clamp(rankIndex);
        Set<Element> explicit = ladder.get(i).explicitShow();
        if (explicit != null) return explicit;
        EnumSet<Element> out = EnumSet.noneOf(Element.class);
        if (seeksSupport(i)) out.add(Element.SUPPORTERS);
        if (i >= firstSupportEntryIndex()) out.add(Element.VASSALS);
        out.add(Element.LIEGE);   // the console only prints it when a liege actually exists
        return out;
    }

    // ---- promotion ----------------------------------------------------------

    /** The op/NPC path: honors every requirement, including COMMAND (knighting, baron-making). */
    public Result promote(ServerPlayer player) {
        NobleData d = getOrCreate(player);
        int cur = d.rankIndex;
        if (cur >= ladder.size() - 1) return Result.fail(d.name + " already holds the highest rank.");
        Rank next = ladder.get(cur + 1);

        switch (next.requirement()) {
            case COMMAND -> { /* always allowed for an op/NPC */ }
            case SUPPORT -> {
                if (d.supporters.size() < next.param())
                    return Result.fail(d.name + " needs " + next.param() + " seals from fellow "
                            + ladder.get(cur).name() + "s for " + next.name() + " (has "
                            + d.supporters.size() + "). Peers seal via /nobility seal <them> " + d.name);
            }
            case BUYIN -> {
                Item item = item(Config.BUYIN_ITEM.get());
                if (!consume(player, item, next.param()))
                    return Result.fail("The " + next.name() + " buy-in is " + next.param() + "x "
                            + item.getDescription().getString() + "; " + d.name + " cannot pay it.");
            }
            case SCORE -> {
                if (d.score < next.param())
                    return Result.fail(d.name + " needs score " + next.param() + " for " + next.name()
                            + " (has " + d.score + ").");
            }
            case CROWN -> {
                // (1) same-rank seals, exactly like SUPPORT (these become vassals on ascension below)
                if (d.supporters.size() < next.param())
                    return Result.fail(d.name + " needs " + next.param() + " seals from fellow "
                            + ladder.get(cur).name() + "s for " + next.name() + " (has "
                            + d.supporters.size() + "). Win War 'n Taxes vassalization wars to take them.");
                // (2) faction leadership + territory, only when Easy Factions is present and the gate is on.
                // The raw ModList check (not FactionGate.loaded()) keeps FactionGate unloaded in standalone.
                if (Config.KING_REQUIRES_FACTION.get() && ModList.get().isLoaded("easy_factions")) {
                    MinecraftServer srv = player.getServer();
                    if (!FactionGate.isFactionLeader(srv, d.id))
                        return Result.fail(d.name + " must be the leader of a faction to claim " + next.name() + ".");
                    int claims = FactionGate.factionClaimCount(srv, d.id);
                    int need = Config.KING_FACTION_CLAIM_THRESHOLD.get();
                    if (claims < need)
                        return Result.fail(d.name + "'s faction (" + FactionGate.factionName(srv, d.id)
                                + ") needs " + need + " claimed chunks for " + next.name() + " (has " + claims + ").");
                }
                // (3) the Crown itself (forged from a Shard of the Crown of Unification)
                Item crown = WarNNobility.CROWN.get();
                if (!has(player, crown, 1))
                    return Result.fail(d.name + " must forge and bear a Crown (from a Shard of the Crown "
                            + "of Unification) to be crowned " + next.name() + ".");
                if (Config.KING_CONSUME_CROWN.get()) consume(player, crown, 1);
            }
            case HIGHEST -> {
                return Result.fail("The throne is not promoted into. The first King is crowned "
                        + "automatically; otherwise a King takes it by usurpation (/nobility emperor usurp).");
            }
        }

        // SUPPORT/CROWN ascension: every sealer is sworn in as a sentenced vassal (the realm forming up).
        // The sentence CARRIES OVER: a seal taken by conquest keeps whatever time was left on its War 'n
        // Taxes subjugation (pledgeUntil), so ranking up does not hand the loser a fresh, longer term nor
        // wipe their remaining one. A voluntary seal (no timed sentence) gets the default sentence instead.
        if (next.requirement() == RequirementType.SUPPORT || next.requirement() == RequirementType.CROWN) {
            long fresh = nowMillis() + sentenceMillis();
            for (UUID sup : new ArrayList<>(d.supporters)) {
                NobleData sd = getOrCreate(sup);
                long carried = sd.pledgeUntil > nowMillis() ? sd.pledgeUntil : fresh;
                detachPledge(sd);             // clears any outgoing seal bookkeeping
                sd.liege = d.id;
                sd.liegeUntil = carried;
                sd.pledgeUntil = 0L;          // consumed; the sentence now lives on liegeUntil
                d.vassals.add(sup);
            }
            d.supporters.clear();
        }

        d.rankIndex = cur + 1;
        if (next.entryScore() >= 0) d.score = next.entryScore();

        // lore: the throne sat empty, so the FIRST King to rise is crowned Emperor by acclamation
        String coronation = "";
        if (d.rankIndex == kingIndex() && !emperorEverCrowned && currentEmperor() == null) {
            d.rankIndex = emperorIndex();
            emperorEverCrowned = true;
            coronation = " The throne stood empty, so " + d.name + " is crowned Emperor by acclamation!";
        }
        setDirty();
        return Result.ok(d.name + " ascends to " + rankName(d) + "!" + coronation);
    }

    /** The player-driven path (Chancery Advance button): COMMAND and HIGHEST steps are not self-served. */
    public Result promoteSelf(ServerPlayer player) {
        NobleData d = getOrCreate(player);
        Rank next = nextRank(d.rankIndex);
        if (next == null) return Result.fail(d.name + " already holds the highest rank.");
        if (next.requirement() == RequirementType.COMMAND)
            return Result.fail("Becoming " + next.name() + " is bestowed by an NPC or the realm, "
                    + "not claimed at the table.");
        if (next.requirement() == RequirementType.HIGHEST)
            return Result.fail("The throne is taken by usurpation, not at the table.");
        return promote(player);
    }

    public Result demote(UUID id) {
        NobleData d = getOrCreate(id);
        if (d.rankIndex <= 0) return Result.fail(d.name + " is already at the bottom.");
        d.rankIndex--;
        Rank now = ladder.get(d.rankIndex);
        if (now.entryScore() >= 0) d.score = now.entryScore();
        setDirty();
        return Result.ok(d.name + " is demoted to " + rankName(d) + ".");
    }

    public Result setScore(UUID id, int value) {
        NobleData d = getOrCreate(id);
        d.score = Math.max(0, value);
        setDirty();
        return Result.ok(d.name + "'s score is now " + d.score + ".");
    }

    // ---- admin overrides, identity, domain naming ---------------------------

    /** Admin freedom: set a player to ANY rank by name, ignoring the normal requirements. */
    public Result setRank(ServerPlayer player, String rankName) {
        NobleData d = getOrCreate(player);
        int idx = indexOfRank(rankName);
        if (idx < 0) return Result.fail("Unknown rank '" + rankName + "'. Ranks: " + rankNamesJoined());
        d.rankIndex = idx;
        Rank r = ladder.get(idx);
        if (r.entryScore() >= 0) d.score = r.entryScore();
        setDirty();
        return Result.ok(d.name + " is set to " + rankName(d) + " by decree.");
    }

    /** Ladder index of a rank by its masculine OR feminine name, case-insensitive; -1 if none. */
    public int indexOfRank(String name) {
        String n = name.trim();
        for (int i = 0; i < ladder.size(); i++) {
            if (ladder.get(i).name().equalsIgnoreCase(n)
                    || ladder.get(i).femaleName().equalsIgnoreCase(n)) return i;
        }
        return -1;
    }

    private String rankNamesJoined() {
        List<String> n = new ArrayList<>();
        for (Rank r : ladder) n.add(r.name());
        return String.join(", ", n);
    }

    /** A holder of this rank (Count and up) commands a domain they may name. */
    public boolean holdsDomain(int rankIndex) {
        return clamp(rankIndex) >= firstSupportEntryIndex();
    }

    public String domainKind(int rankIndex) {
        int i = clamp(rankIndex);
        if (i >= kingIndex()) return "realm";
        if (i == dukeIndex()) return "duchy";
        return "county";
    }

    public Result setGender(ServerPlayer player, boolean female) {
        NobleData d = getOrCreate(player);
        d.female = female;
        setDirty();
        return Result.ok(d.name + " is now styled " + rankName(d) + ".");
    }

    public Result setDomainName(ServerPlayer player, String name) {
        NobleData d = getOrCreate(player);
        if (!holdsDomain(d.rankIndex))
            return Result.fail("Only a Count or higher holds a domain to name.");
        d.domainName = name == null ? "" : name.trim();
        setDirty();
        if (d.domainName.isBlank()) return Result.ok(d.name + "'s domain name is cleared.");
        return Result.ok(d.name + " names their " + domainKind(d.rankIndex) + " \"" + d.domainName + "\".");
    }

    /** Admin-set the directive text shown to players AT a rank (overrides the config advanceText). */
    public Result setDirective(String rankName, String text) {
        int idx = indexOfRank(rankName);
        if (idx < 0) return Result.fail("Unknown rank '" + rankName + "'. Ranks: " + rankNamesJoined());
        String key = ladder.get(idx).name().toLowerCase(java.util.Locale.ROOT);
        if (text == null || text.isBlank()) {
            customDirective.remove(key);
            setDirty();
            return Result.ok("Directive for " + ladder.get(idx).name() + " cleared (back to default).");
        }
        customDirective.put(key, text.trim());
        setDirty();
        return Result.ok(ladder.get(idx).name() + " directive set: " + text.trim());
    }

    /** The effective "how to advance from here" text: admin override, else config, else blank. */
    private String effectiveDirective(NobleData d) {
        int cur = clamp(d.rankIndex);
        String custom = customDirective.get(ladder.get(cur).name().toLowerCase(java.util.Locale.ROOT));
        if (custom != null && !custom.isBlank()) return custom;
        return ladder.get(cur).advanceText();
    }

    // ---- seals (voluntary support) ------------------------------------------

    public Result seal(ServerPlayer supporterP, ServerPlayer targetP) {
        NobleData s = getOrCreate(supporterP);
        NobleData t = getOrCreate(targetP);
        if (s.id.equals(t.id)) return Result.fail("A noble cannot seal their own claim.");
        Rank tNext = nextRank(t.rankIndex);
        if (tNext == null || (tNext.requirement() != RequirementType.SUPPORT
                && tNext.requirement() != RequirementType.CROWN))
            return Result.fail(t.name + " is not gathering seals right now.");
        if (s.rankIndex != t.rankIndex)
            return Result.fail("A seal must come from a peer of the same rank (both must be "
                    + rankName(t) + ").");
        if (s.pledgedTo != null) {
            if (s.pledgeForced)
                return Result.fail(s.name + " is bound by conquest to back " + nameOf(s.pledgedTo) + ".");
            return Result.fail(s.name + " already backs " + nameOf(s.pledgedTo)
                    + "; /nobility unseal " + s.name + " first.");
        }
        s.pledgedTo = t.id;
        s.pledgeForced = false;
        t.supporters.add(s.id);
        setDirty();
        return Result.ok(s.name + " seals their support to " + t.name + " (" + t.supporters.size()
                + "/" + tNext.param() + " for " + tNext.name() + "). When " + t.name
                + " ascends, " + s.name + " becomes their vassal.");
    }

    public Result unseal(ServerPlayer supporterP) {
        NobleData s = getOrCreate(supporterP);
        if (s.pledgedTo == null) return Result.fail(s.name + " is not backing anyone.");
        if (s.pledgeForced)
            return Result.fail(s.name + " is bound by conquest and cannot withdraw support.");
        UUID t = s.pledgedTo;
        NobleData td = peek(t);
        if (td != null) td.supporters.remove(s.id);
        s.pledgedTo = null;
        setDirty();
        return Result.ok(s.name + " withdraws support from " + nameOf(t) + ".");
    }

    // ---- subjugation OUTCOME hook (fed by an external war) -------------------

    /**
     * Apply the result of a won vassalization war: {@code defender}'s seal is taken by force and
     * pledged to {@code aggressor}, locked so it cannot be withdrawn. It converts to a real vassal
     * when the aggressor next ascends (Count from Barons, Duke from Counts, King from Dukes).
     *
     * <p>This is the seam to Machiavelli's War 'n Taxes: when that mod resolves a vassalization war,
     * it calls this (or an op/quest does so meanwhile). The fighting lives there; the fealty here.
     */
    public Result subjugate(ServerPlayer aggressorP, ServerPlayer defenderP) {
        getOrCreate(aggressorP);                    // refresh the stored names from the online profiles
        getOrCreate(defenderP);
        return subjugate(aggressorP.getUUID(), defenderP.getUUID());
    }

    /**
     * UUID-core subjugation, so an external war (Machiavelli's War 'n Taxes) can bind a loser even
     * while they are offline. Same rules as the player overload: peers of the same rank only, and the
     * aggressor's next step must be a seal-based (SUPPORT/CROWN) claim for the forced seal to mean
     * anything. Names must already exist (a colony owner has logged in at least once).
     */
    public Result subjugate(UUID aggressorId, UUID defenderId) {
        NobleData a = getOrCreate(aggressorId);
        NobleData df = getOrCreate(defenderId);
        if (a.id.equals(df.id)) return Result.fail("A noble cannot subjugate themselves.");

        // (1) SAME RANK — a peer-seal toward the aggressor's OWN ascension. It is only PROVISIONAL: the
        // loser is not a vassal yet (equals cannot hold equals) and is sworn in only when the aggressor
        // rises past them. Requires the aggressor's next step to actually be seal-based (Count/Duke/King).
        if (a.rankIndex == df.rankIndex) {
            Rank aNext = nextRank(a.rankIndex);
            if (aNext == null || (aNext.requirement() != RequirementType.SUPPORT
                    && aNext.requirement() != RequirementType.CROWN))
                return Result.fail(a.name + " (" + rankName(a) + ") has no seal-based claim that a peer's "
                        + "submission could advance.");
            detachPledge(df);                          // break any seal the defender currently holds out
            df.pledgedTo = a.id;
            df.pledgeForced = true;
            a.supporters.add(df.id);
            setDirty();
            return Result.ok(df.name + " is subjugated by " + a.name + "! Their seal is taken by force and "
                    + "cannot be relinquished — but this is NOT yet vassalage. " + df.name + " is a provisional "
                    + "seal (" + a.supporters.size() + "/" + aNext.param() + " toward " + aNext.name() + ") and "
                    + "becomes " + a.name + "'s sworn vassal only when " + a.name + " ascends to " + aNext.name()
                    + ". If the subjugation runs out first, the hold lapses.");
        }

        // (2) HIGHER beats LOWER — the aggressor already outranks the loser, so there is nothing to wait
        // for: the loser is sworn in AT ONCE as a direct, sentenced vassal (never provisional). Only a
        // vassal-holding rank (Count+) may do this; a lesser lord has no seat to keep a vassal in.
        if (a.rankIndex > df.rankIndex) {
            if (a.rankIndex < firstSupportEntryIndex())
                return Result.fail(a.name + " (" + rankName(a) + ") holds no rank that keeps vassals; only "
                        + "a Count or higher may bind a lesser noble by conquest.");
            detachPledge(df);                                        // a bound vassal stops backing anyone
            if (df.liege != null && !df.liege.equals(a.id)) {        // conquest transfers existing vassalage
                NobleData prev = peek(df.liege);
                if (prev != null) prev.vassals.remove(df.id);
            }
            df.liege = a.id;
            df.liegeUntil = nowMillis() + sentenceMillis();          // WarTaxesBridge then pins it to WnT time
            a.vassals.add(df.id);
            setDirty();
            return Result.ok(df.name + " (" + rankName(df) + ") is conquered by " + a.name + " ("
                    + rankName(a) + ") and sworn in at once as a sentenced vassal — a superior taking a "
                    + "lesser noble is direct vassalage, not a provisional seal.");
        }

        // (3) LOWER beats HIGHER — a lesser noble cannot hold their better as a vassal, and a superior's
        // seal does nothing for the lesser's own climb. No nobility bond forms (any War 'n Taxes tribute
        // won on the field stands on its own, separate from the seal/vassal ladder).
        return Result.fail(a.name + " (" + rankName(a) + ") cannot bind " + df.name + " (" + rankName(df)
                + "): a lesser noble may not vassalize their better.");
    }

    // ---- vassalage (sentences, breaking free) -------------------------------

    /** A liege voluntarily frees a vassal (or an op does). Ignores the sentence. */
    public Result releaseVassal(UUID liegeId, UUID vassalId) {
        NobleData L = getOrCreate(liegeId);
        if (!L.vassals.remove(vassalId))
            return Result.fail(nameOf(vassalId) + " is not a vassal of " + L.name + ".");
        NobleData v = getOrCreate(vassalId);
        if (v.liege != null && v.liege.equals(L.id)) { v.liege = null; v.liegeUntil = 0; }
        setDirty();
        return Result.ok(L.name + " releases " + v.name + " from service.");
    }

    /**
     * True if {@code sealHolder} is currently a PROVISIONAL forced seal pledged to {@code overlord}: won by
     * conquest but not yet sworn into vassalage (the overlord has not ascended). Drives the book's
     * "(Provisional)" tag. A holder who has already converted to a sworn vassal returns false.
     */
    public boolean isProvisionalSeal(UUID overlordId, UUID sealHolderId) {
        NobleData v = peek(sealHolderId);
        return v != null && v.pledgeForced && overlordId.equals(v.pledgedTo);
    }

    // ---- War 'n Taxes mirror hooks (WarTaxesBridge) --------------------------

    /**
     * Undo a War 'n Taxes-originated bond when that vassalization ends. Only a PROVISIONAL seal (one still
     * awaiting the backer's ascension) walks free here: the loser served out the war sentence without ever
     * being sworn in, so the hold lapses. A seal that already CONVERTED into full vassalage on ascension is
     * permanent and is NOT released by the War 'n Taxes sentence ending (it lives on only until they
     * renounce after their carried-over sentence, or the liege frees them). Only touches bonds still
     * pointing at {@code overlord}. Returns {@code null} when there was nothing provisional of ours to drop.
     */
    @Nullable
    public Result releaseWnTBond(UUID overlordId, UUID vassalId) {
        NobleData o = peek(overlordId);
        NobleData v = peek(vassalId);
        if (v == null) return null;
        if (v.pledgeForced && overlordId.equals(v.pledgedTo)) {   // provisional seal → the hold lapses
            if (o != null) o.supporters.remove(vassalId);
            v.pledgedTo = null;
            v.pledgeForced = false;
            v.pledgeUntil = 0L;
            setDirty();
            return Result.ok(v.name + "'s provisional seal to " + nameOf(overlordId)
                    + " lapses as the subjugation ends (never sworn into vassalage).");
        }
        return null;   // converted vassals are permanent; the ending WnT sentence does not free them
    }

    /**
     * Pin a War 'n Taxes-originated subjugation sentence to that mod's own end time, so our "cannot break
     * free" window matches the war there. Applies in BOTH phases of the same conquest:
     * <ul>
     *   <li>while still a provisional forced seal → stored in {@link NobleData#pledgeUntil}, so the
     *       remaining time is preserved and CARRIES OVER when the backer ascends and it becomes vassalage;</li>
     *   <li>once sworn in as a vassal → stored in {@link NobleData#liegeUntil}, the live sentence.</li>
     * </ul>
     */
    public void setWnTSentence(UUID overlordId, UUID vassalId, long untilEpochMs) {
        NobleData v = peek(vassalId);
        if (v == null) return;
        boolean changed = false;
        if (v.pledgeForced && overlordId.equals(v.pledgedTo) && v.pledgeUntil != untilEpochMs) {
            v.pledgeUntil = untilEpochMs; changed = true;      // provisional phase (for carry-over)
        }
        if (overlordId.equals(v.liege) && v.liegeUntil != untilEpochMs) {
            v.liegeUntil = untilEpochMs; changed = true;       // sworn phase (live sentence)
        }
        if (changed) setDirty();
    }

    /** A vassal tries to break free. Allowed only once the sentence has elapsed. */
    public Result leaveLiege(ServerPlayer vassalP) {
        NobleData v = getOrCreate(vassalP);
        if (v.liege == null) return Result.fail(v.name + " serves no liege.");
        long now = nowMillis();
        if (now < v.liegeUntil)
            return Result.fail(v.name + " is bound to " + nameOf(v.liege) + " for "
                    + humanize(v.liegeUntil - now) + " more.");
        UUID old = v.liege;
        NobleData L = peek(old);
        if (L != null) L.vassals.remove(v.id);
        v.liege = null;
        v.liegeUntil = 0;
        setDirty();
        return Result.ok(v.name + " breaks free from " + nameOf(old)
                + " (and keeps their title by de jure).");
    }

    /**
     * A vassal openly RENOUNCES their liege. Allowed only once the (carried-over) sentence has elapsed;
     * until then they are bound by conquest and cannot. Renouncing is an act of rebellion, not a clean
     * exit: the ex-liege's colonies immediately treat the renouncer as HOSTILE (MineColonies guards turn
     * on them), and the only way back to fealty is for the ex-liege to win a fresh subjugation war. The
     * renouncer keeps their own title.
     */
    public Result renounce(ServerPlayer rebelP) {
        NobleData v = getOrCreate(rebelP);
        if (v.liege == null) return Result.fail(v.name + " serves no liege to renounce.");
        long now = nowMillis();
        if (now < v.liegeUntil)
            return Result.fail(v.name + " is bound by sentence to " + nameOf(v.liege) + " for "
                    + humanize(v.liegeUntil - now) + " more and cannot renounce until it is served.");
        UUID old = v.liege;
        NobleData L = peek(old);
        if (L != null) L.vassals.remove(v.id);
        v.liege = null;
        v.liegeUntil = 0;
        setDirty();
        // Consequence: open hostility. Mark the rebel hostile in every colony the ex-liege owns.
        com.newtl.warnnobility.integration.ColonyHostility.makeHostile(rebelP.getServer(), old, v.id);
        return Result.ok(v.name + " renounces fealty to " + nameOf(old) + " and stands in open defiance! "
                + nameOf(old) + "'s colonies now treat " + v.name + " as hostile — only a won subjugation "
                + "war will bring them to heel again.");
    }

    private void detachPledge(NobleData s) {
        if (s.pledgedTo != null) {
            NobleData prev = peek(s.pledgedTo);
            if (prev != null) prev.supporters.remove(s.id);
        }
        s.pledgedTo = null;
        s.pledgeForced = false;
    }

    // ---- the throne ---------------------------------------------------------

    @Nullable
    public NobleData currentEmperor() {
        int ei = emperorIndex();
        for (NobleData d : nobles.values()) if (d.rankIndex == ei) return d;
        return null;
    }

    private EmperorBid bid(UUID challenger) {
        return challenges.computeIfAbsent(challenger, k -> new EmperorBid());
    }

    public Result emperorThrone(UUID challenger, boolean held) {
        bid(challenger).throne = held;
        setDirty();
        return Result.ok(nameOf(challenger) + (held ? " now holds the throne room of King's Landing."
                : " has lost the throne room of King's Landing."));
    }

    public Result emperorWar(UUID challenger, boolean won) {
        bid(challenger).warWon = won;
        setDirty();
        return Result.ok(nameOf(challenger) + (won ? " has won their war against the Emperor."
                : "'s war against the Emperor is marked unwon."));
    }

    /** True once every duchy that is sworn directly to the Emperor has been vassalized by the King. */
    public boolean hasSeizedAllDuchies(NobleData challenger, NobleData emperor) {
        boolean anyDuchy = false;
        for (UUID v : emperor.vassals) {
            NobleData vd = peek(v);
            if (vd != null && vd.rankIndex == dukeIndex()) {
                anyDuchy = true;
                if (!challenger.vassals.contains(v)) return false;
            }
        }
        return anyDuchy;   // with no imperial duchies to take, there is nothing to seize
    }

    public List<String> usurpStatus(NobleData challenger) {
        NobleData emp = currentEmperor();
        EmperorBid b = challenges.get(challenger.id);
        boolean captured = emp != null && hasSeizedAllDuchies(challenger, emp);
        boolean throne = b != null && b.throne;
        boolean war = b != null && b.warWon;
        List<String> out = new ArrayList<>();
        out.add(cond("Vassalized every Imperial duchy", captured));
        out.add(cond("Holds the King's Landing throne", throne));
        out.add(cond("Won the war on the Emperor", war));
        return out;
    }

    public Result emperorUsurp(ServerPlayer challengerP) {
        NobleData c = getOrCreate(challengerP);
        if (c.rankIndex != kingIndex())
            return Result.fail("Only a King may usurp the throne (" + c.name + " is " + rankName(c) + ").");
        NobleData emp = currentEmperor();
        if (emp == null) return Result.fail("There is no sitting Emperor to challenge.");
        if (emp.id.equals(c.id)) return Result.fail(c.name + " already wears the crown.");

        EmperorBid b = challenges.get(c.id);
        boolean captured = hasSeizedAllDuchies(c, emp);
        boolean throne = b != null && b.throne;
        boolean war = b != null && b.warWon;
        if (!(captured && throne && war)) {
            return Result.fail("The challenge is incomplete. " + String.join("  ", usurpStatus(c)));
        }

        emp.rankIndex = kingIndex();        // the old Emperor is cast down to King
        c.rankIndex = emperorIndex();
        challenges.remove(c.id);
        setDirty();
        return Result.ok(c.name + " seizes the throne! " + emp.name + " is cast down to "
                + rankName(emp) + ". Long live Emperor " + c.name + "!");
    }

    private static String cond(String label, boolean done) {
        return (done ? "[x] " : "[ ] ") + label;
    }

    // ---- console summary ----------------------------------------------------

    public String progressHint(NobleData d) {
        int cur = d.rankIndex;
        if (cur >= ladder.size() - 1) return "You wear the crown. Defend it; a King may yet challenge you.";
        String directive = effectiveDirective(d);
        if (!directive.isBlank()) return directive;
        Rank next = ladder.get(cur + 1);
        return switch (next.requirement()) {
            case COMMAND -> "To become " + next.name() + ": earn it in the world (a boss, a quest, "
                    + "joining the civilization); an NPC or op then elevates you.";
            case SUPPORT -> "To become " + next.name() + ": gather " + next.param() + " seals from fellow "
                    + ladder.get(cur).name() + "s (you have " + d.supporters.size()
                    + "), then advance. Each sealer becomes your vassal.";
            case BUYIN -> "To become " + next.name() + ": pay " + next.param() + "x "
                    + item(Config.BUYIN_ITEM.get()).getDescription().getString() + ", then advance.";
            case SCORE -> "To become " + next.name() + ": reach score " + next.param() + " (you have "
                    + d.score + ").";
            case CROWN -> "To become " + next.name() + ": gather " + next.param() + " seals from fellow "
                    + ladder.get(cur).name() + "s (you have " + d.supporters.size() + "), lead a faction holding "
                    + Config.KING_FACTION_CLAIM_THRESHOLD.get() + " claimed chunks, and bear a Crown, then advance.";
            case HIGHEST -> "To take the throne: vassalize every Imperial duchy, hold King's Landing, "
                    + "and win the war on the Emperor, then /nobility emperor usurp " + d.name + ".";
        };
    }

    /** The visible standing lines for the Chancery console, filtered by this rank's visible set. */
    public List<String> standingLines(NobleData d) {
        Set<Element> vis = visibleFor(d.rankIndex);
        long now = nowMillis();
        List<String> out = new ArrayList<>();
        out.add("Title: " + rankName(d));
        if (vis.contains(Element.SCORE)) out.add("Score: " + d.score);
        if (vis.contains(Element.LIEGE) && d.liege != null) {
            String bound = now < d.liegeUntil ? " (bound " + humanize(d.liegeUntil - now) + ")" : " (free to leave)";
            out.add("Liege: " + nameOf(d.liege) + bound);
        }
        if (vis.contains(Element.VASSALS)) out.add("Vassals: " + d.vassals.size());
        if (vis.contains(Element.SUPPORTERS)) {
            Rank next = nextRank(d.rankIndex);
            int need = (next != null && (next.requirement() == RequirementType.SUPPORT
                    || next.requirement() == RequirementType.CROWN)) ? next.param() : 0;
            out.add("Seals pledged: " + d.supporters.size() + (need > 0 ? "/" + need : ""));
        }
        return out;
    }

    /** Label for the Advance button, or empty if the next step is not claimed at the table. */
    public String advanceLabel(NobleData d) {
        if (!playerMayAdvance(d)) return "";
        Rank next = nextRank(d.rankIndex);
        if (next == null) return "";
        String label = "Become " + next.name();
        if (next.requirement() == RequirementType.SUPPORT || next.requirement() == RequirementType.CROWN) {
            label += " (" + d.supporters.size() + "/" + next.param() + " seals)";
        } else if (next.requirement() == RequirementType.BUYIN) {
            label += " (pay " + next.param() + "x "
                    + item(Config.BUYIN_ITEM.get()).getDescription().getString() + ")";
        }
        return label;
    }

    // ---- helpers ------------------------------------------------------------

    private static long nowMillis() {
        return System.currentTimeMillis();
    }

    /** "2d 3h", "45m 10s", etc. Compact, for sentence messages. */
    private static String humanize(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400; s %= 86400;
        long h = s / 3600;  s %= 3600;
        long m = s / 60;    s %= 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    private static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    private boolean has(ServerPlayer player, Item item, int amount) {
        var inv = player.getInventory();
        int have = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) have += inv.getItem(i).getCount();
        }
        return have >= amount;
    }

    private boolean consume(ServerPlayer player, Item item, int amount) {
        var inv = player.getInventory();
        int have = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) have += inv.getItem(i).getCount();
        }
        if (have < amount) return false;
        int rem = amount;
        for (int i = 0; i < inv.getContainerSize() && rem > 0; i++) {
            ItemStack st = inv.getItem(i);
            if (st.is(item)) {
                int take = Math.min(rem, st.getCount());
                st.shrink(take);
                rem -= take;
            }
        }
        return true;
    }

    // ---- persistence --------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        ListTag nobleList = new ListTag();
        for (NobleData d : nobles.values()) nobleList.add(d.save());
        tag.put("nobles", nobleList);

        ListTag bidList = new ListTag();
        for (Map.Entry<UUID, EmperorBid> e : challenges.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("id", e.getKey());
            c.putBoolean("throne", e.getValue().throne);
            c.putBoolean("warWon", e.getValue().warWon);
            bidList.add(c);
        }
        tag.put("challenges", bidList);

        ListTag dirList = new ListTag();
        for (Map.Entry<String, String> e : customDirective.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putString("rank", e.getKey());
            c.putString("text", e.getValue());
            dirList.add(c);
        }
        tag.put("directives", dirList);

        tag.putBoolean("emperorEverCrowned", emperorEverCrowned);
        return tag;
    }

    public static NobilityManager load(CompoundTag tag, HolderLookup.Provider provider) {
        NobilityManager m = new NobilityManager();
        ListTag nobleList = tag.getList("nobles", Tag.TAG_COMPOUND);
        for (int i = 0; i < nobleList.size(); i++) {
            NobleData d = NobleData.load(nobleList.getCompound(i));
            m.nobles.put(d.id, d);
        }
        ListTag bidList = tag.getList("challenges", Tag.TAG_COMPOUND);
        for (int i = 0; i < bidList.size(); i++) {
            CompoundTag c = bidList.getCompound(i);
            EmperorBid b = new EmperorBid();
            b.throne = c.getBoolean("throne");
            b.warWon = c.getBoolean("warWon");
            m.challenges.put(c.getUUID("id"), b);
        }
        ListTag dirList = tag.getList("directives", Tag.TAG_COMPOUND);
        for (int i = 0; i < dirList.size(); i++) {
            CompoundTag c = dirList.getCompound(i);
            m.customDirective.put(c.getString("rank"), c.getString("text"));
        }
        m.emperorEverCrowned = tag.getBoolean("emperorEverCrowned");
        return m;
    }
}

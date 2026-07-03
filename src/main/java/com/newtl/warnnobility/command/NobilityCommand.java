package com.newtl.warnnobility.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.newtl.warnnobility.nobility.NobilityManager;
import com.newtl.warnnobility.nobility.NobleData;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * /nobility ... command tree. Ops only (level 2), which also covers command blocks and quest
 * reward commands (FTBQuests etc.) and the eventual War 'n Taxes hooks, which run /nobility
 * subjugate and /nobility emperor ... with elevated permission.
 */
public final class NobilityCommand {

    private NobilityCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("nobility")
                .requires(src -> src.hasPermission(2))

                // --- rank changes ---
                .then(Commands.literal("promote")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(NobilityCommand::promote)))
                .then(Commands.literal("demote")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(NobilityCommand::demote)))
                // admin freedom: jump a player to ANY rank, ignoring the normal requirements
                .then(Commands.literal("setrank")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("rank", StringArgumentType.word())
                                        .executes(NobilityCommand::setRank))))
                .then(Commands.literal("setscore")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("value", IntegerArgumentType.integer(0))
                                        .executes(NobilityCommand::setScore))))

                // --- voluntary seals ---
                .then(Commands.literal("seal")
                        .then(Commands.argument("supporter", EntityArgument.player())
                                .then(Commands.argument("target", EntityArgument.player())
                                        .executes(NobilityCommand::seal))))
                .then(Commands.literal("unseal")
                        .then(Commands.argument("supporter", EntityArgument.player())
                                .executes(NobilityCommand::unseal)))

                // --- forced seal: the OUTCOME of a vassalization war (admin/quest/War'n'Taxes) ---
                .then(Commands.literal("subjugate")
                        .then(Commands.argument("aggressor", EntityArgument.player())
                                .then(Commands.argument("defender", EntityArgument.player())
                                        .executes(NobilityCommand::subjugate))))

                // --- vassalage ---
                .then(Commands.literal("vassal")
                        .then(Commands.literal("release")
                                .then(Commands.argument("liege", EntityArgument.player())
                                        .then(Commands.argument("vassal", EntityArgument.player())
                                                .executes(NobilityCommand::vassalRelease))))
                        .then(Commands.literal("leave")
                                .then(Commands.argument("vassal", EntityArgument.player())
                                        .executes(NobilityCommand::vassalLeave))))

                // --- the throne: conditions fed in by external war/siege outcomes ---
                .then(Commands.literal("emperor")
                        .then(Commands.literal("throne")
                                .then(Commands.argument("challenger", EntityArgument.player())
                                        .then(Commands.argument("held", BoolArgumentType.bool())
                                                .executes(NobilityCommand::emperorThrone))))
                        .then(Commands.literal("war")
                                .then(Commands.argument("challenger", EntityArgument.player())
                                        .then(Commands.argument("won", BoolArgumentType.bool())
                                                .executes(NobilityCommand::emperorWar))))
                        .then(Commands.literal("usurp")
                                .then(Commands.argument("challenger", EntityArgument.player())
                                        .executes(NobilityCommand::emperorUsurp)))
                        .then(Commands.literal("status")
                                .then(Commands.argument("challenger", EntityArgument.player())
                                        .executes(NobilityCommand::emperorStatus))))

                // --- identity, domain naming, custom directives ---
                .then(Commands.literal("gender")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.literal("male").executes(c -> gender(c, false)))
                                .then(Commands.literal("female").executes(c -> gender(c, true)))))
                .then(Commands.literal("domain")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.literal("clear").executes(c -> domain(c, "")))
                                .then(Commands.argument("name", StringArgumentType.greedyString())
                                        .executes(c -> domain(c, StringArgumentType.getString(c, "name"))))))
                .then(Commands.literal("directive")
                        .then(Commands.argument("rank", StringArgumentType.word())
                                .then(Commands.literal("clear").executes(c -> directive(c, "")))
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(c -> directive(c, StringArgumentType.getString(c, "text"))))))

                // --- read-only ---
                .then(Commands.literal("info")
                        .executes(ctx -> info(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> info(ctx, EntityArgument.getPlayer(ctx, "player"))))));

        // Player-facing rebellion. Kept OUT of the op-only /nobility tree above so any bound player may
        // renounce their liege once their sentence is served — at the cost of the ex-liege's colonies
        // turning hostile (see NobilityManager.renounce).
        dispatcher.register(Commands.literal("renounce")
                .executes(ctx -> report(ctx, mgr(ctx).renounce(ctx.getSource().getPlayerOrException()))));
    }

    private static NobilityManager mgr(CommandContext<CommandSourceStack> ctx) {
        return NobilityManager.get(ctx.getSource().getServer());
    }

    private static int report(CommandContext<CommandSourceStack> ctx, NobilityManager.Result r) {
        if (r.success()) {
            ctx.getSource().sendSuccess(() -> Component.literal(r.message()).withStyle(ChatFormatting.GOLD), true);
            return 1;
        }
        ctx.getSource().sendFailure(Component.literal(r.message()));
        return 0;
    }

    private static int promote(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).promote(EntityArgument.getPlayer(ctx, "player")));
    }

    private static int demote(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).demote(EntityArgument.getPlayer(ctx, "player").getUUID()));
    }

    private static int setRank(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).setRank(EntityArgument.getPlayer(ctx, "player"),
                StringArgumentType.getString(ctx, "rank")));
    }

    private static int gender(CommandContext<CommandSourceStack> ctx, boolean female) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).setGender(EntityArgument.getPlayer(ctx, "player"), female));
    }

    private static int domain(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).setDomainName(EntityArgument.getPlayer(ctx, "player"), name));
    }

    private static int directive(CommandContext<CommandSourceStack> ctx, String text) {
        return report(ctx, mgr(ctx).setDirective(StringArgumentType.getString(ctx, "rank"), text));
    }

    private static int setScore(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        UUID id = EntityArgument.getPlayer(ctx, "player").getUUID();
        return report(ctx, mgr(ctx).setScore(id, IntegerArgumentType.getInteger(ctx, "value")));
    }

    private static int seal(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).seal(
                EntityArgument.getPlayer(ctx, "supporter"),
                EntityArgument.getPlayer(ctx, "target")));
    }

    private static int unseal(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).unseal(EntityArgument.getPlayer(ctx, "supporter")));
    }

    private static int subjugate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).subjugate(
                EntityArgument.getPlayer(ctx, "aggressor"),
                EntityArgument.getPlayer(ctx, "defender")));
    }

    private static int vassalRelease(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).releaseVassal(
                EntityArgument.getPlayer(ctx, "liege").getUUID(),
                EntityArgument.getPlayer(ctx, "vassal").getUUID()));
    }

    private static int vassalLeave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).leaveLiege(EntityArgument.getPlayer(ctx, "vassal")));
    }

    private static int emperorThrone(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).emperorThrone(
                EntityArgument.getPlayer(ctx, "challenger").getUUID(),
                BoolArgumentType.getBool(ctx, "held")));
    }

    private static int emperorWar(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).emperorWar(
                EntityArgument.getPlayer(ctx, "challenger").getUUID(),
                BoolArgumentType.getBool(ctx, "won")));
    }

    private static int emperorUsurp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return report(ctx, mgr(ctx).emperorUsurp(EntityArgument.getPlayer(ctx, "challenger")));
    }

    private static int emperorStatus(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        NobilityManager m = mgr(ctx);
        NobleData c = m.getOrCreate(EntityArgument.getPlayer(ctx, "challenger"));
        StringBuilder sb = new StringBuilder("Usurpation progress for " + c.name + ":");
        for (String line : m.usurpStatus(c)) sb.append("\n  ").append(line);
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        NobilityManager m = mgr(ctx);
        NobleData d = m.getOrCreate(player);
        StringBuilder vassals = new StringBuilder();
        for (UUID v : d.vassals) {
            if (vassals.length() > 0) vassals.append(", ");
            vassals.append(m.nameOf(v));
        }
        StringBuilder sb = new StringBuilder("== " + d.name + " ==\n");
        sb.append("Title: ").append(m.rankName(d)).append("\n");
        if (m.holdsDomain(d.rankIndex) && !d.domainName.isBlank())
            sb.append("Domain: ").append(d.domainName).append("\n");
        sb.append("Liege: ").append(m.nameOf(d.liege)).append("\n");
        sb.append("Vassals: ").append(vassals.length() == 0 ? "none" : vassals).append("\n");
        sb.append("Seals pledged: ").append(d.supporters.size()).append("\n");
        if (d.pledgedTo != null) {
            sb.append("Backing: ").append(m.nameOf(d.pledgedTo))
                    .append(d.pledgeForced ? " (bound by conquest)" : "").append("\n");
        }
        sb.append(m.progressHint(d));
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }
}

package com.newtl.warnnobility.domain;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** {@code /domains refresh} (ops): recompute and re-push every nobility domain to clients now. */
public final class DomainCommand {

    private DomainCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("domains")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("refresh").executes(ctx -> {
                    int n = DomainEngine.recompute(ctx.getSource().getServer());
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("Domains: synced " + n + " domains to clients."), true);
                    return n;
                })));
    }
}

package com.cobbleverse.cobblesyncbridge.command;

import com.cobbleverse.cobblesyncbridge.cobblemon.CobblemonReflectionAdapter;
import com.cobbleverse.cobblesyncbridge.sync.SyncCoordinator;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public final class BridgeCommands {
    private BridgeCommands() {}

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                SyncCoordinator coordinator,
                                CobblemonReflectionAdapter adapter) {
        dispatcher.register(CommandManager.literal("cobblesyncbridge")
                .requires(src -> src.hasPermissionLevel(2))
                .then(CommandManager.literal("status")
                        .executes(ctx -> {
                            ctx.getSource().sendFeedback(() -> Text.literal(coordinator.statusSummary()), false);
                            return 1;
                        }))
                .then(CommandManager.literal("inspect")
                        .executes(ctx -> {
                            String inspect = adapter.inspectRuntime();
                            ctx.getSource().sendFeedback(() -> Text.literal("Reflection inspect dumped to server log."), false);
                            com.cobbleverse.cobblesyncbridge.CobbleSyncBridgeMod.LOGGER.info(inspect);
                            return 1;
                        }))
                .then(CommandManager.literal("resync")
                        .then(CommandManager.argument("player", EntityArgumentType.player())
                                .executes(ctx -> {
                                    var player = EntityArgumentType.getPlayer(ctx, "player");
                                    coordinator.resyncNow(player);
                                    ctx.getSource().sendFeedback(() ->
                                            Text.literal("Queued resync for " + player.getName().getString()), false);
                                    return 1;
                                }))));
    }
}

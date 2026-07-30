package com.dwinovo.numen.core.server;

import com.dwinovo.numen.api.ServerBrainConfiguration;
import com.dwinovo.numen.core.Constants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Operator-only bridge to the external server brain's runtime configuration.
 *
 * <p>The command never changes a Forge-local value. Every invocation publishes
 * a short-lived request to the sidecar; only a matching applied acknowledgement
 * is presented as a successful switch.
 */
public final class BrainConfigCommands {

    private static volatile MinecraftServer activeServer;
    private static volatile long lastExpirySweepGameTime = Long.MIN_VALUE;

    static {
        ServerBrainConfiguration.addReportListener(
                BrainConfigCommands::onReport);
    }

    private BrainConfigCommands() {}

    public static void register(
            CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("momo")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("brain")
                        .executes(BrainConfigCommands::status)
                        .then(Commands.literal("status")
                                .executes(BrainConfigCommands::status))
                        .then(Commands.literal("list")
                                .executes(BrainConfigCommands::list))
                        .then(Commands.literal("set")
                                .then(Commands.argument(
                                                "model",
                                                StringArgumentType.word())
                                        .suggests(
                                                BrainConfigCommands
                                                        ::suggestModels)
                                        .then(Commands.argument(
                                                        "reasoning",
                                                        StringArgumentType
                                                                .word())
                                                .suggests(
                                                        BrainConfigCommands
                                                                ::suggestReasoning)
                                                .executes(
                                                        BrainConfigCommands
                                                                ::set))))));
    }

    public static void bindServer(MinecraftServer server) {
        activeServer = server;
        lastExpirySweepGameTime = Long.MIN_VALUE;
    }

    public static void unbindServer(MinecraftServer server) {
        if (activeServer == server) activeServer = null;
    }

    /** One cheap sweep per second delivers explicit offline-brain timeouts. */
    public static void serverTick(MinecraftServer server) {
        long gameTime = server.overworld().getGameTime();
        if (!shouldSweep(gameTime, lastExpirySweepGameTime)) return;
        lastExpirySweepGameTime = gameTime;
        ServerBrainConfiguration.expireRequests();
    }

    static boolean shouldSweep(long gameTime, long lastSweepGameTime) {
        return lastSweepGameTime == Long.MIN_VALUE
                || gameTime < lastSweepGameTime
                || gameTime - lastSweepGameTime >= 20L;
    }

    private static int status(
            CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerBrainConfiguration.Snapshot snapshot =
                ServerBrainConfiguration.snapshot();
        source.sendSuccess(
                () -> Component.literal(formatStatus(snapshot)),
                false);
        return publish(
                source,
                ServerBrainConfiguration.Action.GET,
                null,
                null);
    }

    private static int list(
            CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerBrainConfiguration.Snapshot snapshot =
                ServerBrainConfiguration.snapshot();
        source.sendSuccess(
                () -> Component.literal(formatCatalog(snapshot)),
                false);
        return publish(
                source,
                ServerBrainConfiguration.Action.LIST,
                null,
                null);
    }

    private static int set(
            CommandContext<CommandSourceStack> context) {
        return publish(
                context.getSource(),
                ServerBrainConfiguration.Action.SET,
                StringArgumentType.getString(context, "model"),
                StringArgumentType.getString(context, "reasoning"));
    }

    private static int publish(
            CommandSourceStack source,
            ServerBrainConfiguration.Action action,
            String model,
            String reasoning) {
        ServerBrainConfiguration.Requester requester =
                requester(source);
        long gameTime = source.getServer().overworld().getGameTime();
        final String requestId;
        try {
            requestId = ServerBrainConfiguration.request(
                    action,
                    model,
                    reasoning,
                    requester,
                    gameTime);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            source.sendFailure(Component.literal(
                    "无法提交 server-brain 请求：" + ex.getMessage()));
            return 0;
        }

        String detail = action == ServerBrainConfiguration.Action.SET
                ? "model=" + model + ", reasoning=" + reasoning
                : action.wireName();
        source.sendSuccess(
                () -> Component.literal(
                        "已提交 server-brain 请求 "
                                + shortId(requestId)
                                + "（"
                                + detail
                                + "）；等待确认，当前尚未切换。"),
                false);
        return 1;
    }

    private static ServerBrainConfiguration.Requester requester(
            CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            return ServerBrainConfiguration.Requester.player(
                    player.getUUID(),
                    player.getGameProfile().getName());
        }
        return ServerBrainConfiguration.Requester.console(
                source.getTextName());
    }

    private static CompletableFuture<Suggestions> suggestModels(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        ServerBrainConfiguration.Snapshot snapshot =
                ServerBrainConfiguration.snapshot();
        if (snapshot == null) return builder.buildFuture();
        return SharedSuggestionProvider.suggest(
                snapshot.catalog().stream()
                        .map(ServerBrainConfiguration.CatalogEntry::model),
                builder);
    }

    private static CompletableFuture<Suggestions> suggestReasoning(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) {
        ServerBrainConfiguration.Snapshot snapshot =
                ServerBrainConfiguration.snapshot();
        if (snapshot == null) return builder.buildFuture();
        String model = StringArgumentType.getString(context, "model");
        List<String> reasoning = snapshot.catalog().stream()
                .filter(entry -> entry.model().equals(model))
                .findFirst()
                .map(ServerBrainConfiguration.CatalogEntry::reasoning)
                .orElse(List.of());
        return SharedSuggestionProvider.suggest(reasoning.stream(), builder);
    }

    private static void onReport(
            ServerBrainConfiguration.Report report) {
        MinecraftServer server = activeServer;
        if (server == null) {
            if (report.requester() != null) {
                Constants.LOG.warn(
                        "[brain-config] response {} arrived while the server was stopped: {}",
                        shortId(report.requestId()),
                        report.error());
            }
            return;
        }
        server.execute(() -> deliverReport(server, report));
    }

    private static void deliverReport(
            MinecraftServer server,
            ServerBrainConfiguration.Report report) {
        if (report.requester() == null) {
            // Startup reports refresh the authoritative cache but are not a
            // response to a human command.
            Constants.LOG.info(
                    "[brain-config] server-brain state: {}",
                    formatActive(report.snapshot()));
            return;
        }

        String text;
        ChatFormatting color;
        if (!report.success()) {
            text = "server-brain 请求 "
                    + shortId(report.requestId())
                    + " 未生效："
                    + report.error()
                    + stateSuffix(report.snapshot());
            color = ChatFormatting.RED;
        } else if (report.action()
                == ServerBrainConfiguration.Action.SET) {
            text = "server-brain 已确认并生效 "
                    + shortId(report.requestId())
                    + "："
                    + formatActive(report.snapshot());
            color = ChatFormatting.GREEN;
        } else if (report.action()
                == ServerBrainConfiguration.Action.LIST) {
            text = formatCatalog(report.snapshot());
            color = ChatFormatting.AQUA;
        } else {
            text = formatStatus(report.snapshot());
            color = ChatFormatting.AQUA;
        }
        Component message = Component.literal(text).withStyle(color);
        ServerBrainConfiguration.Requester requester = report.requester();
        if ("player".equals(requester.kind())) {
            ServerPlayer player = server.getPlayerList()
                    .getPlayer(requester.uuid());
            if (player != null) {
                player.sendSystemMessage(message);
                return;
            }
        } else {
            server.sendSystemMessage(message);
            return;
        }
        Constants.LOG.info(
                "[brain-config] response for offline operator {}: {}",
                requester.name(),
                text);
    }

    static String formatStatus(
            ServerBrainConfiguration.Snapshot snapshot) {
        if (snapshot == null) {
            return "尚未收到 server-brain 权威状态。";
        }
        return "server-brain 当前状态（上次确认）："
                + formatActive(snapshot)
                + "，待确认请求="
                + ServerBrainConfiguration.pendingCount();
    }

    static String formatCatalog(
            ServerBrainConfiguration.Snapshot snapshot) {
        if (snapshot == null) {
            return "尚未收到 server-brain 模型目录。";
        }
        StringBuilder result = new StringBuilder(
                "server-brain 可用模型/思考强度（revision=")
                .append(snapshot.revision())
                .append("）：");
        if (snapshot.catalog().isEmpty()) {
            return result.append("\n- （目录为空）").toString();
        }
        for (ServerBrainConfiguration.CatalogEntry entry
                : snapshot.catalog()) {
            result.append("\n- ")
                    .append(entry.model())
                    .append(" [")
                    .append(String.join(", ", entry.reasoning()))
                    .append(']');
        }
        return result.toString();
    }

    private static String formatActive(
            ServerBrainConfiguration.Snapshot snapshot) {
        if (snapshot == null) return "无权威状态";
        return "model="
                + snapshot.model()
                + ", reasoning="
                + snapshot.reasoning()
                + ", revision="
                + snapshot.revision();
    }

    private static String stateSuffix(
            ServerBrainConfiguration.Snapshot snapshot) {
        return snapshot == null
                ? ""
                : "；当前仍为 " + formatActive(snapshot);
    }

    private static String shortId(String requestId) {
        if (requestId == null || requestId.isBlank()) return "(startup)";
        String clean = requestId.toLowerCase(Locale.ROOT);
        return clean.length() <= 12
                ? clean
                : clean.substring(0, 12);
    }
}

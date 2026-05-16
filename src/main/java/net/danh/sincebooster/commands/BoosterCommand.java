package net.danh.sincebooster.commands;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.experience.Profession;
import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.data.PlayerDataHandler;
import net.danh.sincebooster.manager.Booster;
import net.danh.sincebooster.utils.ColorUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles all command registrations and command execution logic for SinceBooster using Paper's Brigadier API.
 */
public class BoosterCommand {

    private final SinceBooster plugin;

    public BoosterCommand(SinceBooster plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the command tree during the server's command lifecycle event.
     */
    public void registerCommands() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(Commands.literal("booster")
                    .executes(ctx -> {
                        if (ctx.getSource().getExecutor() instanceof Player p) plugin.getBoosterGUI().open(p);
                        return 1;
                    })
                    .then(Commands.literal("reload")
                            .requires(s -> s.getSender().hasPermission("sincebooster.admin"))
                            .executes(ctx -> {
                                plugin.reloadFiles();
                                ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("admin.reload", "Configuration reloaded successfully!")));
                                return 1;
                            })
                    )
                    .then(Commands.literal("view")
                            .executes(ctx -> {
                                if (ctx.getSource().getExecutor() instanceof Player p) plugin.getBoosterGUI().open(p);
                                return 1;
                            })
                            .then(Commands.argument("target", StringArgumentType.word())
                                    .requires(s -> s.getSender().hasPermission("sincebooster.admin"))
                                    .suggests((ctx, builder) -> suggestPlayers(builder))
                                    .executes(ctx -> {
                                        if (ctx.getSource().getExecutor() instanceof Player viewer) {
                                            String tName = StringArgumentType.getString(ctx, "target");
                                            Player target = Bukkit.getPlayer(tName);
                                            if (target != null) {
                                                plugin.getBoosterGUI().open(viewer, target);
                                            } else {
                                                viewer.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("admin.invalid_player", "Invalid or offline player.")));
                                            }
                                        }
                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("remove")
                            .requires(s -> s.getSender().hasPermission("sincebooster.admin"))
                            .then(Commands.argument("target", StringArgumentType.word())
                                    .suggests((ctx, builder) -> suggestPlayers(builder))
                                    .then(Commands.argument("booster_id", StringArgumentType.word())
                                            .suggests((ctx, builder) -> {
                                                builder.suggest("all");
                                                try {
                                                    String tName = StringArgumentType.getString(ctx, "target");
                                                    Player t = Bukkit.getPlayer(tName);
                                                    if (t != null) {
                                                        List<Booster> list = plugin.getBoosterManager().getActiveBoosters(t.getUniqueId());
                                                        if (list != null) {
                                                            for (Booster b : list) builder.suggest(b.getId());
                                                        }
                                                    }
                                                } catch (IllegalArgumentException ignored) {
                                                }
                                                return builder.buildFuture();
                                            })
                                            .executes(ctx -> {
                                                String tName = StringArgumentType.getString(ctx, "target");
                                                String bId = StringArgumentType.getString(ctx, "booster_id");
                                                Player t = Bukkit.getPlayer(tName);

                                                if (t == null) {
                                                    ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("admin.invalid_player", "Invalid or offline player.")));
                                                    return 0;
                                                }

                                                if (bId.equalsIgnoreCase("all")) {
                                                    plugin.getBoosterManager().removeAllBoosters(t);
                                                    ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(
                                                            plugin.getMessagesFile().getString("admin.remove_all_success", "Removed all boosters from <target>.").replace("<target>", t.getName())
                                                    ));
                                                } else {
                                                    boolean success = plugin.getBoosterManager().removeBooster(t, bId);
                                                    if (success) {
                                                        ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(
                                                                plugin.getMessagesFile().getString("admin.remove_success", "Removed <id> from <target>.")
                                                                        .replace("<target>", t.getName())
                                                                        .replace("<id>", bId)
                                                        ));
                                                    } else {
                                                        ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(
                                                                plugin.getMessagesFile().getString("share.booster_not_found", "Booster <id> not found.").replace("<id>", bId)
                                                        ));
                                                    }
                                                }
                                                return 1;
                                            })
                                    )
                            )
                    )
                    .then(Commands.literal("give")
                            .requires(s -> s.getSender().hasPermission("sincebooster.admin"))
                            .then(Commands.argument("target", StringArgumentType.word())
                                    .suggests((ctx, builder) -> suggestPlayers(builder))
                                    .then(Commands.argument("id", StringArgumentType.word())
                                            .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(0.1))
                                                    .then(Commands.literal("duration")
                                                            .then(Commands.argument("seconds", LongArgumentType.longArg(1))
                                                                    .executes(ctx -> executeGive(ctx, false, null))
                                                                    .then(Commands.argument("profession", StringArgumentType.word())
                                                                            .suggests((ctx, builder) -> suggestProfessions(builder))
                                                                            .executes(ctx -> executeGive(ctx, false, StringArgumentType.getString(ctx, "profession")))
                                                                    )
                                                            )
                                                    )
                                                    .then(Commands.literal("permanent")
                                                            .executes(ctx -> executeGive(ctx, true, null))
                                                            .then(Commands.argument("profession", StringArgumentType.word())
                                                                    .suggests((ctx, builder) -> suggestProfessions(builder))
                                                                    .executes(ctx -> executeGive(ctx, true, StringArgumentType.getString(ctx, "profession")))
                                                            )
                                                    )
                                            )
                                    )
                            )
                    )
                    .then(Commands.literal("share")
                            .then(Commands.literal("offline")
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) {
                                            ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("command.players_only", "&cOnly players can use this command.")));
                                            return 0;
                                        }

                                        if (!(p.hasPermission("sincebooster.share.offline") && p.hasPermission("sincebooster.share"))) {
                                            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.offline_no_perm", "No permission for offline share.")));
                                            return 0;
                                        }

                                        PlayerDataHandler.PlayerSession session = plugin.getPlayerDataHandler().getSession(p.getUniqueId());
                                        if (session != null) {
                                            boolean current = session.isOfflineShareEnabled();
                                            session.setOfflineShareEnabled(!current);
                                            plugin.getPlayerDataHandler().saveData(p.getUniqueId(), false);

                                            if (!current) {
                                                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.offline_toggle_on", "Offline sharing enabled.")));
                                            } else {
                                                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.offline_toggle_off", "Offline sharing disabled.")));
                                            }
                                        }
                                        return 1;
                                    })
                            )
                            .then(Commands.argument("target", StringArgumentType.word())
                                    .suggests((ctx, builder) -> suggestPlayers(builder))
                                    .then(Commands.literal("all").executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) {
                                            ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("command.players_only", "&cOnly players can use this command.")));
                                            return 0;
                                        }
                                        Player t = Bukkit.getPlayer(StringArgumentType.getString(ctx, "target"));
                                        if (!p.hasPermission("sincebooster.share")) {
                                            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.no_permission", "No share permission.")));
                                            return 0;
                                        }
                                        if (validateShare(p, t)) {
                                            List<Booster> list = plugin.getBoosterManager().getActiveBoosters(p.getUniqueId());
                                            plugin.getBoosterManager().getShareManager().sendInviteBatch(p, t, list);
                                        }
                                        return 1;
                                    }))
                                    .then(Commands.argument("booster_id", StringArgumentType.word())
                                            .suggests((ctx, builder) -> {
                                                if (ctx.getSource().getExecutor() instanceof Player p) {
                                                    List<Booster> list = plugin.getBoosterManager().getActiveBoosters(p.getUniqueId());
                                                    if (list != null) for (Booster b : list) builder.suggest(b.getId());
                                                }
                                                return builder.buildFuture();
                                            })
                                            .executes(ctx -> {
                                                if (!(ctx.getSource().getExecutor() instanceof Player p)) {
                                                    ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("command.players_only", "&cOnly players can use this command.")));
                                                    return 0;
                                                }
                                                Player t = Bukkit.getPlayer(StringArgumentType.getString(ctx, "target"));
                                                String bId = StringArgumentType.getString(ctx, "booster_id");
                                                if (!p.hasPermission("sincebooster.share")) {
                                                    p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.no_permission", "No share permission.")));
                                                    return 0;
                                                }
                                                if (validateShare(p, t)) {
                                                    plugin.getBoosterManager().getShareManager().sendInvite(p, t, bId);
                                                }
                                                return 1;
                                            })
                                    )
                            )
                    )
                    .then(Commands.literal("accept")
                            .then(Commands.argument("sender", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        if (ctx.getSource().getExecutor() instanceof Player p) {
                                            for (String name : plugin.getBoosterManager().getShareManager().getPendingSenders(p))
                                                builder.suggest(name);
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) {
                                            ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("command.players_only", "&cOnly players can use this command.")));
                                            return 0;
                                        }
                                        Player s = Bukkit.getPlayer(StringArgumentType.getString(ctx, "sender"));
                                        if (s != null)
                                            plugin.getBoosterManager().getShareManager().acceptInvite(p, s);
                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("leave")
                            .then(Commands.argument("owner", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        if (ctx.getSource().getExecutor() instanceof Player p) {
                                            for (String name : plugin.getBoosterManager().getShareManager().getOwnersSharingWith(p))
                                                builder.suggest(name);
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        if (!(ctx.getSource().getExecutor() instanceof Player p)) {
                                            ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("command.players_only", "&cOnly players can use this command.")));
                                            return 0;
                                        }
                                        Player owner = Bukkit.getPlayer(StringArgumentType.getString(ctx, "owner"));
                                        if (owner != null)
                                            plugin.getBoosterManager().getShareManager().leaveShare(p, owner);
                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("kick")
                            .then(Commands.argument("booster_id", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        if (ctx.getSource().getExecutor() instanceof Player p) {
                                            List<Booster> list = plugin.getBoosterManager().getActiveBoosters(p.getUniqueId());
                                            if (list != null) for (Booster b : list) builder.suggest(b.getId());
                                        }
                                        return builder.buildFuture();
                                    })
                                    .then(Commands.argument("target", StringArgumentType.word())
                                            .suggests((ctx, builder) -> {
                                                if (ctx.getSource().getExecutor() instanceof Player p) {
                                                    String bId = StringArgumentType.getString(ctx, "booster_id");
                                                    List<Booster> list = plugin.getBoosterManager().getActiveBoosters(p.getUniqueId());
                                                    if (list != null) {
                                                        for (Booster b : list) {
                                                            if (b.getId().equalsIgnoreCase(bId)) {
                                                                for (UUID uid : b.getSharedPlayers()) {
                                                                    OfflinePlayer op = Bukkit.getOfflinePlayer(uid);
                                                                    if (op.getName() != null)
                                                                        builder.suggest(op.getName());
                                                                }
                                                                break;
                                                            }
                                                        }
                                                    }
                                                }
                                                return builder.buildFuture();
                                            })
                                            .executes(ctx -> {
                                                if (!(ctx.getSource().getExecutor() instanceof Player p)) {
                                                    ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("command.players_only", "&cOnly players can use this command.")));
                                                    return 0;
                                                }
                                                String bId = StringArgumentType.getString(ctx, "booster_id");
                                                Player t = Bukkit.getPlayer(StringArgumentType.getString(ctx, "target"));
                                                if (t != null)
                                                    plugin.getBoosterManager().getShareManager().kickShare(p, bId, t);
                                                return 1;
                                            })
                                    )
                            )
                    )
                    .then(Commands.literal("set-rate")
                            .requires(s -> s.getSender().hasPermission("sincebooster.admin"))
                            .then(Commands.argument("type", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        builder.suggest("decay");
                                        builder.suggest("owner");
                                        builder.suggest("receiver");
                                        builder.suggest("limit");
                                        return builder.buildFuture();
                                    })
                                    .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.0))
                                            .executes(ctx -> {
                                                String type = StringArgumentType.getString(ctx, "type");
                                                double val = DoubleArgumentType.getDouble(ctx, "value");
                                                plugin.getBoosterManager().getShareManager().setGlobalValue(typeToConfig(type), val);
                                                ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("admin.set_rate_global", "Set <type> to <value>").replace("<type>", type).replace("<value>", String.valueOf(val))));
                                                return 1;
                                            })
                                            .then(Commands.argument("target", StringArgumentType.word())
                                                    .suggests((ctx, builder) -> suggestPlayers(builder))
                                                    .executes(ctx -> {
                                                        String type = StringArgumentType.getString(ctx, "type");
                                                        double val = DoubleArgumentType.getDouble(ctx, "value");
                                                        Player t = Bukkit.getPlayer(StringArgumentType.getString(ctx, "target"));
                                                        if (t != null) {
                                                            PlayerDataHandler.PlayerSession s = plugin.getPlayerDataHandler().getSession(t.getUniqueId());
                                                            if (s != null) {
                                                                switch (type) {
                                                                    case "decay" -> s.setShareRate(val);
                                                                    case "owner" -> s.setOwnerBuffRate(val);
                                                                    case "receiver" -> s.setReceiverBuffRate(val);
                                                                    case "limit" -> s.setShareLimit((int) val);
                                                                }
                                                                plugin.getPlayerDataHandler().saveData(t.getUniqueId(), false);
                                                            }
                                                            ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("admin.set_rate_player", "Set <target>'s <type> to <value>").replace("<type>", type).replace("<target>", t.getName()).replace("<value>", String.valueOf(val))));
                                                        }
                                                        return 1;
                                                    })
                                            )
                                    )
                            )
                    )
                    .build(), "Booster commands"
            );
        });
    }

    private boolean validateShare(Player p, Player t) {
        if (t == null) {
            if (p != null)
                p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.invalid_target", "Invalid target.")));
            return false;
        }
        if (p != null && t.getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage(ColorUtils.parseWithPrefix(plugin.getMessagesFile().getString("share.self_interaction", "Cannot interact with yourself.")));
            return false;
        }
        return true;
    }

    private CompletableFuture<Suggestions> suggestPlayers(SuggestionsBuilder builder) {
        for (Player p : Bukkit.getOnlinePlayers()) builder.suggest(p.getName());
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> suggestProfessions(SuggestionsBuilder builder) {
        if (Bukkit.getPluginManager().isPluginEnabled("MMOCore"))
            for (Profession p : MMOCore.plugin.professionManager.getAll()) builder.suggest(p.getId());
        return builder.buildFuture();
    }

    private int executeGive(CommandContext<CommandSourceStack> ctx, boolean isPerm, String prof) {
        String tName = StringArgumentType.getString(ctx, "target");
        Player t = Bukkit.getPlayer(tName);
        if (t == null) return 0;
        String id = StringArgumentType.getString(ctx, "id");
        double mult = DoubleArgumentType.getDouble(ctx, "multiplier");
        long sec = isPerm ? 0 : LongArgumentType.getLong(ctx, "seconds");
        plugin.getBoosterManager().giveBooster(t, id, mult, sec, prof, isPerm);
        return 1;
    }

    private String typeToConfig(String type) {
        return switch (type) {
            case "decay" -> "default-rate";
            case "owner" -> "default-owner-buff";
            case "receiver" -> "default-receiver-buff";
            case "limit" -> "default-share-limit";
            default -> "";
        };
    }
}

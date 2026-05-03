package net.danh.sincebooster;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.experience.Profession;
import net.danh.sincebooster.data.DatabaseManager;
import net.danh.sincebooster.data.PlayerDataHandler;
import net.danh.sincebooster.events.JoinQuit;
import net.danh.sincebooster.gui.BoosterGUI;
import net.danh.sincebooster.gui.ManageShareGUI;
import net.danh.sincebooster.gui.ShareGUI;
import net.danh.sincebooster.hooks.MMOCoreHook;
import net.danh.sincebooster.manager.Booster;
import net.danh.sincebooster.manager.BoosterManager;
import net.danh.sincebooster.utils.ColorUtils;
import net.danh.sincebooster.utils.ConfigUtils;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Objects;

public final class SinceBooster extends JavaPlugin {
    private static SinceBooster plugin;
    private MiniMessage miniMessage;

    private ConfigUtils configFile;
    private ConfigUtils messagesFile;

    private BoosterManager boosterManager;
    private PlayerDataHandler playerDataHandler;
    private DatabaseManager databaseManager;
    private BoosterGUI boosterGUI;

    public static SinceBooster getPlugin() {
        return plugin;
    }

    @Override
    public void onEnable() {
        plugin = this;
        miniMessage = MiniMessage.miniMessage();

        // 1. Load Configs
        configFile = new ConfigUtils(this, "config.yml");
        messagesFile = new ConfigUtils(this, "messages.yml");

        // 2. Initialize Data
        databaseManager = new DatabaseManager(this);
        playerDataHandler = new PlayerDataHandler(this);

        // 3. Initialize Managers
        boosterManager = new BoosterManager(this);
        boosterGUI = new BoosterGUI(this);

        // 4. Register Listeners
        registerListeners(
                new JoinQuit(this),
                boosterGUI,
                new ShareGUI(this),
                new ManageShareGUI(this)
        );

        // 5. Hook MMOCore
        if (Bukkit.getPluginManager().isPluginEnabled("MMOCore")) {
            getServer().getPluginManager().registerEvents(new MMOCoreHook(this), this);
            getLogger().info("Hooked into MMOCore successfully!");
        } else {
            getLogger().warning("MMOCore not found! Exp multipliers will not work.");
        }

        // 6. Register Commands
        registerCommands();

        // 7. Start Tasks
        boosterGUI.startUpdateTask();
        startAutoSaveTask();
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (playerDataHandler != null) {
            getLogger().info("Saving player data...");
            playerDataHandler.saveAllSync();
        }
        if (databaseManager != null) databaseManager.close();
    }

    public void reloadFiles() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory topInv = p.getOpenInventory().getTopInventory();
            // CRITICAL FIX: Use getHolder(false) to prevent expensive block state snapshots
            InventoryHolder holder = topInv.getHolder(false);

            // Đóng GUI dựa trên InventoryHolder thay vì Title để tránh xung đột
            if (holder instanceof BoosterGUI.BoosterHolder ||
                    holder instanceof ShareGUI.PlayerSelectorHolder ||
                    holder instanceof ShareGUI.BoosterSelectorHolder ||
                    holder instanceof ManageShareGUI.ManageHolder) {

                p.closeInventory();
                p.sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("booster.gui.closed_on_reload")));
            }
        }
        configFile.reload();
        messagesFile.reload();
        if (boosterManager != null) boosterManager.getShareManager().reloadConfigValues();
    }

    private void registerListeners(Listener @NonNull ... listeners) {
        for (Listener l : listeners) getServer().getPluginManager().registerEvents(l, this);
    }

    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(Commands.literal("booster")
                    .executes(ctx -> {
                        if (ctx.getSource().getExecutor() instanceof Player p) boosterGUI.open(p);
                        return 1;
                    })
                    .then(Commands.literal("reload")
                            .requires(s -> s.getSender().hasPermission("sincebooster.admin"))
                            .executes(ctx -> {
                                reloadFiles();
                                ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("admin.reload")));
                                return 1;
                            })
                    )
                    .then(Commands.literal("view")
                            .executes(ctx -> {
                                if (ctx.getSource().getExecutor() instanceof Player p) boosterGUI.open(p);
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
                                                boosterGUI.open(viewer, target);
                                            } else {
                                                viewer.sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("admin.invalid_player")));
                                            }
                                        }
                                        return 1;
                                    })
                            )
                    )
                    // ==========================================
                    //            NEW COMMAND: REMOVE
                    // ==========================================
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
                                                        List<Booster> list = boosterManager.getActiveBoosters(t.getUniqueId());
                                                        if (list != null) {
                                                            for (Booster b : list) {
                                                                builder.suggest(b.getId());
                                                            }
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
                                                    ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("admin.invalid_player")));
                                                    return 0;
                                                }

                                                // Xử lý xóa
                                                if (bId.equalsIgnoreCase("all")) {
                                                    boosterManager.removeAllBoosters(t);
                                                    ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(
                                                            messagesFile.getString("admin.remove_all_success").replace("<target>", t.getName())
                                                    ));
                                                } else {
                                                    boolean success = boosterManager.removeBooster(t, bId);
                                                    if (success) {
                                                        ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(
                                                                messagesFile.getString("admin.remove_success")
                                                                        .replace("<target>", t.getName())
                                                                        .replace("<id>", bId)
                                                        ));
                                                    } else {
                                                        ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(
                                                                messagesFile.getString("share.booster_not_found").replace("<id>", bId)
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
                                                                            .executes(ctx -> {
                                                                                String prof = StringArgumentType.getString(ctx, "profession");
                                                                                return executeGive(ctx, false, prof);
                                                                            })
                                                                    )
                                                            )
                                                    )
                                                    .then(Commands.literal("permanent")
                                                            .executes(ctx -> executeGive(ctx, true, null))
                                                            .then(Commands.argument("profession", StringArgumentType.word())
                                                                    .suggests((ctx, builder) -> suggestProfessions(builder))
                                                                    .executes(ctx -> {
                                                                        String prof = StringArgumentType.getString(ctx, "profession");
                                                                        return executeGive(ctx, true, prof);
                                                                    })
                                                            )
                                                    )
                                            )
                                    )
                            )
                    )
                    .then(Commands.literal("share")
                            .then(Commands.literal("offline")
                                    .executes(ctx -> {
                                        Player p = (Player) ctx.getSource().getExecutor();
                                        if (p == null) return 0;

                                        // Check quyền Offline Share
                                        if (!(p.hasPermission("sincebooster.share.offline") && p.hasPermission("sincebooster.share"))) {
                                            p.sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("share.offline_no_perm")));
                                            return 0;
                                        }

                                        PlayerDataHandler.PlayerSession session = playerDataHandler.getSession(p.getUniqueId());
                                        if (session != null) {
                                            boolean current = session.isOfflineShareEnabled();
                                            session.setOfflineShareEnabled(!current); // Đảo ngược trạng thái
                                            playerDataHandler.saveData(p.getUniqueId(), false);

                                            if (!current) {
                                                p.sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("share.offline_toggle_on")));
                                            } else {
                                                p.sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("share.offline_toggle_off")));
                                            }
                                        }
                                        return 1;
                                    })
                            )
                            .then(Commands.argument("target", StringArgumentType.word())
                                    .suggests((ctx, builder) -> suggestPlayers(builder))
                                    .then(Commands.literal("all").executes(ctx -> {
                                        Player p = (Player) ctx.getSource().getExecutor();
                                        Player t = Bukkit.getPlayer(StringArgumentType.getString(ctx, "target"));
                                        if (p != null && !p.hasPermission("sincebooster.share")) {
                                            p.sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("share.no_permission")));
                                            return 0;
                                        }
                                        if (validateShare(p, t)) {
                                            List<Booster> list = boosterManager.getActiveBoosters(Objects.requireNonNull(p).getUniqueId());
                                            boosterManager.getShareManager().sendInviteBatch(p, t, list);
                                        }
                                        return 1;
                                    }))
                                    .then(Commands.argument("booster_id", StringArgumentType.word())
                                            .suggests((ctx, builder) -> {
                                                if (ctx.getSource().getExecutor() instanceof Player p) {
                                                    List<Booster> list = boosterManager.getActiveBoosters(p.getUniqueId());
                                                    if (list != null) for (Booster b : list) builder.suggest(b.getId());
                                                }
                                                return builder.buildFuture();
                                            })
                                            .executes(ctx -> {
                                                Player p = (Player) ctx.getSource().getExecutor();
                                                Player t = Bukkit.getPlayer(StringArgumentType.getString(ctx, "target"));
                                                String bId = StringArgumentType.getString(ctx, "booster_id");
                                                if (p != null && !p.hasPermission("sincebooster.share")) {
                                                    p.sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("share.no_permission")));
                                                    return 0;
                                                }
                                                if (validateShare(p, t)) {
                                                    boosterManager.getShareManager().sendInvite(Objects.requireNonNull(p), t, bId);
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
                                            for (String name : boosterManager.getShareManager().getPendingSenders(p))
                                                builder.suggest(name);
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        Player p = (Player) ctx.getSource().getExecutor();
                                        Player s = Bukkit.getPlayer(StringArgumentType.getString(ctx, "sender"));
                                        if (s != null && p != null) boosterManager.getShareManager().acceptInvite(p, s);
                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("leave")
                            .then(Commands.argument("owner", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        if (ctx.getSource().getExecutor() instanceof Player p) {
                                            for (String name : boosterManager.getShareManager().getOwnersSharingWith(p))
                                                builder.suggest(name);
                                        }
                                        return builder.buildFuture();
                                    })
                                    .executes(ctx -> {
                                        Player p = (Player) ctx.getSource().getExecutor();
                                        Player owner = Bukkit.getPlayer(StringArgumentType.getString(ctx, "owner"));
                                        if (owner != null && p != null)
                                            boosterManager.getShareManager().leaveShare(p, owner);
                                        return 1;
                                    })
                            )
                    )
                    .then(Commands.literal("kick")
                            .then(Commands.argument("booster_id", StringArgumentType.word())
                                    .suggests((ctx, builder) -> {
                                        if (ctx.getSource().getExecutor() instanceof Player p) {
                                            List<Booster> list = boosterManager.getActiveBoosters(p.getUniqueId());
                                            if (list != null) for (Booster b : list) builder.suggest(b.getId());
                                        }
                                        return builder.buildFuture();
                                    })
                                    .then(Commands.argument("target", StringArgumentType.word())
                                            .suggests((ctx, builder) -> {
                                                if (ctx.getSource().getExecutor() instanceof Player p) {
                                                    String bId = StringArgumentType.getString(ctx, "booster_id");
                                                    List<Booster> list = boosterManager.getActiveBoosters(p.getUniqueId());
                                                    if (list != null) {
                                                        for (Booster b : list) {
                                                            if (b.getId().equalsIgnoreCase(bId)) {
                                                                for (java.util.UUID uid : b.getSharedPlayers()) {
                                                                    org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uid);
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
                                                Player p = (Player) ctx.getSource().getExecutor();
                                                String bId = StringArgumentType.getString(ctx, "booster_id");
                                                Player t = Bukkit.getPlayer(StringArgumentType.getString(ctx, "target"));
                                                if (t != null && p != null)
                                                    boosterManager.getShareManager().kickShare(p, bId, t);
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
                                                boosterManager.getShareManager().setGlobalValue(typeToConfig(type), val);
                                                ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("admin.set_rate_global").replace("<type>", type).replace("<value>", String.valueOf(val))));
                                                return 1;
                                            })
                                            .then(Commands.argument("target", StringArgumentType.word())
                                                    .suggests((ctx, builder) -> suggestPlayers(builder))
                                                    .executes(ctx -> {
                                                        String type = StringArgumentType.getString(ctx, "type");
                                                        double val = DoubleArgumentType.getDouble(ctx, "value");
                                                        Player t = Bukkit.getPlayer(StringArgumentType.getString(ctx, "target"));
                                                        if (t != null) {
                                                            PlayerDataHandler.PlayerSession s = playerDataHandler.getSession(t.getUniqueId());
                                                            if (s != null) {
                                                                switch (type) {
                                                                    case "decay" -> s.setShareRate(val);
                                                                    case "owner" -> s.setOwnerBuffRate(val);
                                                                    case "receiver" -> s.setReceiverBuffRate(val);
                                                                    case "limit" -> s.setShareLimit((int) val);
                                                                }
                                                                playerDataHandler.saveData(t.getUniqueId(), false);
                                                            }
                                                            ctx.getSource().getSender().sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("admin.set_rate_player").replace("<type>", type).replace("<target>", t.getName()).replace("<value>", String.valueOf(val))));
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
            if (p != null) p.sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("share.invalid_target")));
            return false;
        }
        if (p != null && t.getUniqueId().equals(p.getUniqueId())) {
            p.sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("share.self_interaction")));
            return false;
        }
        return true;
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestPlayers(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        for (Player p : Bukkit.getOnlinePlayers()) builder.suggest(p.getName());
        return builder.buildFuture();
    }

    private java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestProfessions(com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        if (Bukkit.getPluginManager().isPluginEnabled("MMOCore"))
            for (Profession p : MMOCore.plugin.professionManager.getAll()) builder.suggest(p.getId());
        return builder.buildFuture();
    }

    private int executeGive(com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx, boolean isPerm, String prof) {
        String tName = StringArgumentType.getString(ctx, "target");
        Player t = Bukkit.getPlayer(tName);
        if (t == null) return 0;
        String id = StringArgumentType.getString(ctx, "id");
        double mult = DoubleArgumentType.getDouble(ctx, "multiplier");
        long sec = isPerm ? 0 : LongArgumentType.getLong(ctx, "seconds");
        boosterManager.giveBooster(t, id, mult, sec, prof, isPerm);
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

    private void startAutoSaveTask() {
        long seconds = configFile.getConfig().getLong("auto-save", 300);
        if (seconds <= 0) return;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (playerDataHandler != null) playerDataHandler.saveAllAsync();
            }
        }.runTaskTimerAsynchronously(this, seconds * 20L, seconds * 20L);
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public BoosterManager getBoosterManager() {
        return boosterManager;
    }

    public PlayerDataHandler getPlayerDataHandler() {
        return playerDataHandler;
    }

    public MiniMessage getMiniMessage() {
        return miniMessage;
    }

    public ConfigUtils getMessagesFile() {
        return messagesFile;
    }

    public ConfigUtils getConfigFile() {
        return configFile;
    }

    public BoosterGUI getBoosterGUI() {
        return boosterGUI;
    }
}
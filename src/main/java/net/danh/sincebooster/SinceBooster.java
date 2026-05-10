package net.danh.sincebooster;

import net.danh.sincebooster.commands.BoosterCommand;
import net.danh.sincebooster.data.DatabaseManager;
import net.danh.sincebooster.data.PlayerDataHandler;
import net.danh.sincebooster.events.JoinQuit;
import net.danh.sincebooster.gui.BoosterGUI;
import net.danh.sincebooster.gui.ManageShareGUI;
import net.danh.sincebooster.gui.ShareGUI;
import net.danh.sincebooster.hooks.MMOCoreHook;
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

/**
 * Main plugin class for SinceBooster.
 * Handles the initialization of configuration files, database connections, managers, commands, and listeners.
 */
public final class SinceBooster extends JavaPlugin {
    private static SinceBooster plugin;
    private MiniMessage miniMessage;

    private ConfigUtils configFile;
    private ConfigUtils messagesFile;
    private ConfigUtils guiFile;

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

        configFile = new ConfigUtils(this, "config.yml");
        messagesFile = new ConfigUtils(this, "messages.yml");
        guiFile = new ConfigUtils(this, "gui.yml");

        databaseManager = new DatabaseManager(this);
        playerDataHandler = new PlayerDataHandler(this);

        boosterManager = new BoosterManager(this);
        boosterGUI = new BoosterGUI(this);

        registerListeners(
                new JoinQuit(this),
                boosterGUI,
                new ShareGUI(this),
                new ManageShareGUI(this)
        );

        if (Bukkit.getPluginManager().isPluginEnabled("MMOCore")) {
            getServer().getPluginManager().registerEvents(new MMOCoreHook(this), this);
            getLogger().info(messagesFile.getString("log.hook_mmocore_success", "Hooked into MMOCore successfully!"));
        } else {
            getLogger().warning(messagesFile.getString("log.hook_mmocore_fail", "MMOCore not found! Exp multipliers will not work."));
        }

        new BoosterCommand(this).registerCommands();

        boosterGUI.startUpdateTask();
        startAutoSaveTask();
    }

    @Override
    public void onDisable() {
        Bukkit.getScheduler().cancelTasks(this);
        if (playerDataHandler != null) {
            getLogger().info(messagesFile.getString("log.saving_data", "Saving player data..."));
            playerDataHandler.saveAllSync();
        }
        if (databaseManager != null) databaseManager.close();
    }

    /**
     * Reloads configuration files and safely closes any active Booster GUIs to prevent desync.
     */
    public void reloadFiles() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Inventory topInv = p.getOpenInventory().getTopInventory();
            InventoryHolder holder = topInv.getHolder(false);

            if (holder instanceof BoosterGUI.BoosterHolder ||
                    holder instanceof ShareGUI.PlayerSelectorHolder ||
                    holder instanceof ShareGUI.BoosterSelectorHolder ||
                    holder instanceof ManageShareGUI.ManageHolder) {

                p.closeInventory();
                p.sendMessage(ColorUtils.parseWithPrefix(messagesFile.getString("booster.gui_closed_on_reload", "Menu closed due to server reload.")));
            }
            p.closeDialog();
        }
        configFile.reload();
        messagesFile.reload();
        guiFile.reload();
        if (boosterManager != null) boosterManager.getShareManager().reloadConfigValues();
    }

    private void registerListeners(Listener @NonNull ... listeners) {
        for (Listener l : listeners) getServer().getPluginManager().registerEvents(l, this);
    }

    /**
     * Starts the asynchronous task to auto-save player data to the database at configured intervals.
     */
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

    public ConfigUtils getGuiFile() {
        return guiFile;
    }

    public BoosterGUI getBoosterGUI() {
        return boosterGUI;
    }
}
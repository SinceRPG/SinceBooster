package net.danh.sincebooster.events;

import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.manager.Booster;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;

/**
 * Handles initialization and safe memory cleanup when players connect and disconnect.
 */
public class JoinQuit implements Listener {
    private final SinceBooster plugin;

    public JoinQuit(SinceBooster plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.getPlayerDataHandler().loadData(e.getPlayer());
        plugin.getBoosterManager().getShareManager().updateOfflineSharesOnJoin(e.getPlayer());
    }

    /**
     * Memory Cleanup Task:
     * Validates if the disconnecting player is actively broadcasting offline booster shares to current online players.
     * If absolutely no online player requires their data, the plugin effectively wipes them out of active RAM.
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID quitUUID = e.getPlayer().getUniqueId();
        plugin.getStatBoosterManager().clear(e.getPlayer());
        plugin.getBoosterManager().getShareManager().updateOfflineSharesOnQuit(e.getPlayer());
        plugin.getPlayerDataHandler().saveData(e.getPlayer(), true);

        plugin.getFoliaScheduler().runGlobalLater(() -> {
            plugin.getBoosterManager().getActiveBoosters().keySet().removeIf(ownerId -> {
                if (Bukkit.getPlayer(ownerId) != null) return false;

                boolean stillNeeded = Bukkit.getOnlinePlayers().stream().anyMatch(onlineP -> {
                    List<Booster> boosters = plugin.getBoosterManager().getActiveBoosters(ownerId);
                    return boosters != null && boosters.stream().anyMatch(b -> b.getSharedPlayers().contains(onlineP.getUniqueId()));
                });

                return !stillNeeded;
            });
        }, 40L);
    }
}

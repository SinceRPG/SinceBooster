package net.danh.sincebooster.events;

import net.danh.sincebooster.SinceBooster;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class JoinQuit implements Listener {
    private final SinceBooster plugin;

    public JoinQuit(SinceBooster plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.getPlayerDataHandler().loadData(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.getPlayerDataHandler().saveData(e.getPlayer().getUniqueId(), true);
    }
}
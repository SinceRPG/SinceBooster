package net.danh.sincebooster.utils;

import net.danh.sincebooster.SinceBooster;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.util.concurrent.TimeUnit;

/**
 * Central scheduler bridge for Paper and Folia.
 */
public final class FoliaScheduler {
    private final SinceBooster plugin;
    private final boolean folia;

    public FoliaScheduler(SinceBooster plugin) {
        this.plugin = plugin;
        this.folia = hasFoliaSchedulers();
    }

    public boolean isFolia() {
        return folia;
    }

    public void runGlobal(Runnable runnable) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public void runGlobalLater(Runnable runnable, long delayTicks) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> runnable.run(), Math.max(1L, delayTicks));
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, runnable, delayTicks);
        }
    }

    public void runGlobalTimer(Runnable runnable, long delayTicks, long periodTicks) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> runnable.run(), Math.max(1L, delayTicks), Math.max(1L, periodTicks));
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, runnable, delayTicks, periodTicks);
        }
    }

    public void runEntity(Entity entity, Runnable runnable) {
        if (entity == null) return;
        if (folia) {
            entity.getScheduler().execute(plugin, runnable, null, 1L);
        } else {
            Bukkit.getScheduler().runTask(plugin, runnable);
        }
    }

    public void runAsync(Runnable runnable) {
        if (folia) {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> runnable.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, runnable);
        }
    }

    public void runAsyncTimer(Runnable runnable, long delayTicks, long periodTicks) {
        if (folia) {
            Bukkit.getAsyncScheduler().runAtFixedRate(
                    plugin,
                    task -> runnable.run(),
                    ticksToMillis(delayTicks),
                    ticksToMillis(periodTicks),
                    TimeUnit.MILLISECONDS
            );
        } else {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, runnable, delayTicks, periodTicks);
        }
    }

    public void cancelTasks() {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
        } else {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    private boolean hasFoliaSchedulers() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private long ticksToMillis(long ticks) {
        return Math.max(50L, ticks * 50L);
    }
}

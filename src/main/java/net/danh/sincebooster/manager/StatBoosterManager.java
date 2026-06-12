package net.danh.sincebooster.manager;

import io.lumine.mythic.lib.api.player.EquipmentSlot;
import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.api.stat.modifier.StatModifier;
import io.lumine.mythic.lib.player.modifier.ModifierSource;
import io.lumine.mythic.lib.player.modifier.ModifierType;
import net.danh.sincebooster.SinceBooster;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies active booster-backed MythicLib stat modifiers to online players.
 */
public class StatBoosterManager {
    private static final String MODIFIER_KEY_PREFIX = "sincebooster:";

    private final SinceBooster plugin;
    private final Map<UUID, Map<UUID, AppliedStat>> appliedStats = new ConcurrentHashMap<>();

    public StatBoosterManager(SinceBooster plugin) {
        this.plugin = plugin;
    }

    public void startUpdateTask() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicLib")) {
            plugin.getLogger().warning("MythicLib not found! Stat boosters will not work.");
            return;
        }

        plugin.getFoliaScheduler().runGlobalTimer(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                plugin.getFoliaScheduler().runEntity(player, () -> refresh(player));
            }
        }, 20L, 20L);
    }

    public void refresh(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicLib")) return;

        UUID playerId = player.getUniqueId();
        Map<UUID, AppliedStat> desired = collectDesiredStats(player);
        Map<UUID, AppliedStat> current = appliedStats.computeIfAbsent(playerId, id -> new HashMap<>());
        MMOPlayerData playerData = MMOPlayerData.get(player);

        for (Map.Entry<UUID, AppliedStat> entry : new HashMap<>(current).entrySet()) {
            AppliedStat desiredStat = desired.get(entry.getKey());
            if (desiredStat == null || !desiredStat.equals(entry.getValue())) {
                unregister(playerData, entry.getKey(), entry.getValue());
                current.remove(entry.getKey());
            }
        }

        for (Map.Entry<UUID, AppliedStat> entry : desired.entrySet()) {
            if (current.containsKey(entry.getKey())) continue;
            register(playerData, entry.getKey(), entry.getValue());
            current.put(entry.getKey(), entry.getValue());
        }

        if (current.isEmpty()) appliedStats.remove(playerId);
    }

    public void clear(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicLib")) return;

        Map<UUID, AppliedStat> current = appliedStats.remove(player.getUniqueId());
        if (current == null || current.isEmpty()) return;

        MMOPlayerData playerData = MMOPlayerData.get(player);
        for (Map.Entry<UUID, AppliedStat> entry : current.entrySet()) {
            unregister(playerData, entry.getKey(), entry.getValue());
        }
    }

    public void clearAll() {
        if (!Bukkit.getPluginManager().isPluginEnabled("MythicLib")) return;

        for (Player player : Bukkit.getOnlinePlayers()) {
            clear(player);
        }
        appliedStats.clear();
    }

    private Map<UUID, AppliedStat> collectDesiredStats(Player player) {
        UUID playerId = player.getUniqueId();
        Map<UUID, AppliedStat> desired = new HashMap<>();

        List<Booster> ownBoosters = plugin.getBoosterManager().getActiveBoosters(playerId);
        if (ownBoosters != null) {
            for (Booster booster : ownBoosters) {
                addDesiredStat(desired, booster, playerId);
            }
        }

        Set<Booster> sharedBoosters = plugin.getBoosterManager().getIncomingShares().get(playerId);
        if (sharedBoosters != null) {
            for (Booster booster : sharedBoosters) {
                if (booster.getSharedPlayers().contains(playerId)) {
                    addDesiredStat(desired, booster, playerId);
                }
            }
        }

        return desired;
    }

    private void addDesiredStat(Map<UUID, AppliedStat> desired, Booster booster, UUID receiverId) {
        if (!booster.isValid() || !booster.isStatBooster()) return;

        double amount = plugin.getBoosterManager().getEffectiveStatAmount(booster, receiverId);
        if (amount == 0.0) return;

        UUID modifierId = createModifierId(booster, receiverId);
        desired.put(modifierId, new AppliedStat(booster.getStat(), amount, createModifierKey(booster, receiverId)));
    }

    private UUID createModifierId(Booster booster, UUID receiverId) {
        String raw = createModifierKey(booster, receiverId);
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String createModifierKey(Booster booster, UUID receiverId) {
        return MODIFIER_KEY_PREFIX + booster.getOwnerUUID() + ":" + receiverId + ":" + booster.getId() + ":" + booster.getStat();
    }

    private void register(MMOPlayerData playerData, UUID modifierId, AppliedStat stat) {
        new StatModifier(
                modifierId,
                stat.key(),
                stat.stat(),
                stat.amount(),
                ModifierType.FLAT,
                EquipmentSlot.OTHER,
                ModifierSource.OTHER
        ).register(playerData);
    }

    private void unregister(MMOPlayerData playerData, UUID modifierId, AppliedStat stat) {
        new StatModifier(
                modifierId,
                stat.key(),
                stat.stat(),
                stat.amount(),
                ModifierType.FLAT,
                EquipmentSlot.OTHER,
                ModifierSource.OTHER
        ).unregister(playerData);
    }

    private record AppliedStat(String stat, double amount, String key) {
    }
}

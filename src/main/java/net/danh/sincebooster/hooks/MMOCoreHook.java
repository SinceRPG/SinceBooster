package net.danh.sincebooster.hooks;

import net.Indyuce.mmocore.api.event.PlayerExperienceGainEvent;
import net.Indyuce.mmocore.experience.Profession;
import net.danh.sincebooster.SinceBooster;
import net.danh.sincebooster.manager.Booster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * MMOCore integration that calculates experience modifiers for classes and professions dynamically.
 */
public class MMOCoreHook implements Listener {

    private final SinceBooster plugin;

    public MMOCoreHook(SinceBooster plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onExpGain(PlayerExperienceGainEvent e) {
        Player p = e.getPlayer();
        UUID myUUID = p.getUniqueId();
        Profession profession = e.getProfession();
        double totalMultiplier = 1.0;

        // 1. Scan Personal Boosters (O(1))
        List<Booster> myList = plugin.getBoosterManager().getActiveBoosters(myUUID);
        if (myList != null) {
            for (Booster b : myList) {
                if (isValidBooster(b, profession)) {
                    totalMultiplier += (plugin.getBoosterManager().getShareManager().getFinalMultiplier(b, myUUID) - 1.0);
                }
            }
        }

        // 2. Scan Incoming Shared Boosters (Uses O(1) Cache for Maximum Speed)
        Set<Booster> sharedToMe = plugin.getBoosterManager().getIncomingShares().get(myUUID);
        if (sharedToMe != null) {
            for (Booster b : sharedToMe) {
                if (isValidBooster(b, profession)) {
                    totalMultiplier += (plugin.getBoosterManager().getShareManager().getFinalMultiplier(b, myUUID) - 1.0);
                }
            }
        }

        if (totalMultiplier > 1.0) {
            e.setExperience(e.getExperience() * totalMultiplier);
        }
    }

    private boolean isValidBooster(Booster b, Profession prof) {
        if (!b.isValid()) return false;
        if (b.getProfession() == null) return prof == null;
        if (prof == null) return false;
        return b.getProfession().equalsIgnoreCase(prof.getId());
    }
}

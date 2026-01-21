package net.danh.sincebooster.hooks;

import net.Indyuce.mmocore.api.event.PlayerExperienceGainEvent;
import net.danh.sincebooster.SinceBooster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class MMOCoreHook implements Listener {

    private final SinceBooster plugin;

    public MMOCoreHook(SinceBooster plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onExpGain(PlayerExperienceGainEvent e) {
        Player p = e.getPlayer();

        // Lấy ID profession (nếu là exp main class thì profession sẽ là null hoặc check theo API MMOCore)
        // Trong MMOCore mới, e.getProfession() trả về object Profession.
        String profId = (e.getProfession() != null) ? e.getProfession().getId() : null;

        // Lấy hệ số nhân từ BoosterManager của sincebooster
        double multiplier = plugin.getBoosterManager().getTotalMultiplier(p, profId);

        // Nếu có booster (> 1.0) thì áp dụng
        if (multiplier > 1.0) {
            double originalExp = e.getExperience();
            double newExp = originalExp * multiplier;

            e.setExperience(newExp);

            // Debug (Xóa khi release)
            // p.sendMessage("§7[Debug] Exp gốc: " + originalExp + " | Multiplier: " + multiplier + " | Exp mới: " + newExp);
        }
    }
}
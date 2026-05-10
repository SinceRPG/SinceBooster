package net.danh.sincebooster.utils;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * A utility class designed to build ItemStacks fluidly, supporting all Paper 1.21.x ItemMeta attributes.
 */
public class ItemBuilder {
    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(Material material) {
        this.item = new ItemStack(material != null ? material : Material.STONE);
        this.meta = item.getItemMeta();
    }

    public ItemBuilder name(String name) {
        if (name != null && meta != null) meta.customName(ColorUtils.parse(name));
        return this;
    }

    public ItemBuilder lore(List<String> loreLines) {
        if (loreLines != null && meta != null) {
            List<Component> components = new ArrayList<>();
            for (String line : loreLines) {
                components.add(ColorUtils.parse(line));
            }
            meta.lore(components);
        }
        return this;
    }

    public ItemBuilder customModelData(Integer data) {
        if (data != null && meta != null) {
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setFloats(List.of(data.floatValue()));
            meta.setCustomModelDataComponent(component);
        }
        return this;
    }

    public ItemBuilder glow(boolean glow) {
        if (glow && meta != null) {
            meta.setEnchantmentGlintOverride(true);
        }
        return this;
    }

    public ItemBuilder hideTooltip(boolean hide) {
        if (meta != null) meta.setHideTooltip(hide);
        return this;
    }

    public ItemBuilder addFlag(ItemFlag flag) {
        if (meta != null) meta.addItemFlags(flag);
        return this;
    }

    public ItemBuilder addEnchant(Enchantment enchantment, int level) {
        if (meta != null) meta.addEnchant(enchantment, level, true);
        return this;
    }

    public ItemBuilder setStringData(NamespacedKey key, String value) {
        if (meta != null && key != null && value != null) {
            meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, value);
        }
        return this;
    }

    public ItemStack build() {
        if (meta != null) item.setItemMeta(meta);
        return item;
    }

    /**
     * Constructs an ItemBuilder directly from a ConfigurationSection in a YAML file.
     */
    public static ItemBuilder fromConfig(ConfigurationSection section) {
        if (section == null) return new ItemBuilder(Material.STONE);

        String matName = section.getString("material", "STONE");
        Material mat = Material.matchMaterial(matName.toUpperCase());
        if (mat == null) mat = Material.STONE;

        ItemBuilder builder = new ItemBuilder(mat);

        if (section.contains("name")) builder.name(section.getString("name"));
        if (section.contains("lore")) builder.lore(section.getStringList("lore"));
        if (section.contains("custom_model_data")) builder.customModelData(section.getInt("custom_model_data"));
        if (section.contains("glow") && section.getBoolean("glow")) builder.glow(true);
        if (section.contains("hide_tooltip") && section.getBoolean("hide_tooltip")) builder.hideTooltip(true);

        return builder;
    }
}
package net.danh.sincebooster.utils;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.danh.sincebooster.SinceBooster;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.*;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Advanced ItemBuilder utility using the Builder Pattern.
 * Designed to strictly adhere to the Paper 1.21.x ItemMeta interface.
 * Centralizes configuration parsing and fully customizes Data Components dynamically.
 */
public class ItemBuilder {
    private final SinceBooster plugin;
    private final ItemStack item;
    private final ItemMeta meta;

    public ItemBuilder(SinceBooster plugin, Material material) {
        this.plugin = plugin;
        this.item = new ItemStack(material != null ? material : Material.STONE);
        this.meta = this.item.getItemMeta();
    }

    public ItemBuilder(SinceBooster plugin, ItemStack itemStack) {
        this.plugin = plugin;
        this.item = itemStack.clone();
        this.meta = this.item.getItemMeta();
    }

    /**
     * Helper method to parse amounts that might be an algorithmic range (e.g., "1-5").
     *
     * @param amtStr The amount string to parse.
     * @return The parsed or randomized integer amount.
     */
    public static int parseRandomAmount(String amtStr) {
        try {
            if (amtStr.contains("-")) {
                String[] range = amtStr.split("-");
                int min = Integer.parseInt(range[0].trim());
                int max = Integer.parseInt(range[1].trim());
                if (min > max) {
                    int temp = min;
                    min = max;
                    max = temp;
                }
                return min + new Random().nextInt(max - min + 1);
            } else {
                return Integer.parseInt(amtStr.trim());
            }
        } catch (Exception e) {
            return 1;
        }
    }

    public ItemBuilder amount(int amount) {
        this.item.setAmount(amount);
        return this;
    }

    public ItemBuilder customModelData(int cmd) {
        if (meta != null) meta.setCustomModelData(cmd);
        return this;
    }

    /**
     * Safely applies persistent NBT data securely directly onto the item's meta container.
     */
    public <T, Z> ItemBuilder setTag(NamespacedKey key, PersistentDataType<T, Z> type, Z value) {
        if (meta != null && key != null && value != null) {
            meta.getPersistentDataContainer().set(key, type, value);
        }
        return this;
    }

    /**
     * Parses and applies an extensive range of metadata from a ConfigurationSection.
     * Complies fully with Paper 1.21.x Data Components mapping features cleanly.
     *
     * @param cfg          The config section to read from.
     * @param defName      Default name if none is provided in the config.
     * @param replacements Dynamic placeholder Key-Value pairs replacing elements in names/lore natively.
     * @return The ItemBuilder instance for chaining.
     */
    public ItemBuilder applyConfig(ConfigurationSection cfg, String defName, String... replacements) {
        if (meta == null) return this;

        if (cfg == null) {
            meta.customName(ColorUtils.parse(defName).decoration(TextDecoration.ITALIC, false));
            return this;
        }

        String name = cfg.getString("name", defName);
        for (int i = 0; i < replacements.length; i += 2) {
            if (replacements[i] != null && replacements[i + 1] != null) {
                name = name.replace(replacements[i], replacements[i + 1]);
            }
        }
        meta.customName(ColorUtils.parse(name).decoration(TextDecoration.ITALIC, false));

        if (cfg.contains("item-name")) {
            try {
                String itemName = cfg.getString("item-name");
                for (int i = 0; i < replacements.length; i += 2) {
                    if (replacements[i] != null && replacements[i + 1] != null) {
                        itemName = itemName.replace(replacements[i], replacements[i + 1]);
                    }
                }
                meta.itemName(ColorUtils.parse(itemName).decoration(TextDecoration.ITALIC, false));
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("lore")) {
            List<String> rawLore = cfg.getStringList("lore");
            List<Component> compLore = new ArrayList<>();
            for (String line : rawLore) {
                for (int i = 0; i < replacements.length; i += 2) {
                    if (replacements[i] != null && replacements[i + 1] != null) {
                        line = line.replace(replacements[i], replacements[i + 1]);
                    }
                }
                if (line.contains("\n")) {
                    for (String split : line.split("\n")) {
                        compLore.add(ColorUtils.parse(split).decoration(TextDecoration.ITALIC, false));
                    }
                } else {
                    compLore.add(ColorUtils.parse(line).decoration(TextDecoration.ITALIC, false));
                }
            }
            meta.lore(compLore);
        }

        // Custom Model Data Component mapping (Paper 1.21.x robust implementation)
        if (cfg.contains("custom-model-data")) {
            if (cfg.isConfigurationSection("custom-model-data")) {
                ConfigurationSection cmdSec = cfg.getConfigurationSection("custom-model-data");
                if (cmdSec.contains("value")) {
                    meta.setCustomModelData(cmdSec.getInt("value"));
                }
                try {
                    CustomModelDataComponent cmdc = meta.getCustomModelDataComponent();
                    if (cmdSec.contains("floats")) {
                        List<Float> floats = new ArrayList<>();
                        for (Double d : cmdSec.getDoubleList("floats")) floats.add(d.floatValue());
                        if (!floats.isEmpty()) cmdc.setFloats(floats);
                    }
                    if (cmdSec.contains("strings")) cmdc.setStrings(cmdSec.getStringList("strings"));
                    if (cmdSec.contains("flags")) cmdc.setFlags(cmdSec.getBooleanList("flags"));
                    if (cmdSec.contains("colors")) {
                        List<Color> colors = new ArrayList<>();
                        for (String hex : cmdSec.getStringList("colors")) {
                            try {
                                colors.add(Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16)));
                            } catch (Exception ignored) {
                            }
                        }
                        cmdc.setColors(colors);
                    }
                    meta.setCustomModelDataComponent(cmdc);
                } catch (Throwable ignored) {
                }
            } else {
                meta.setCustomModelData(cfg.getInt("custom-model-data"));
            }
        }

        if (cfg.contains("item-model")) {
            try {
                NamespacedKey key = NamespacedKey.fromString(cfg.getString("item-model"));
                if (key != null) meta.setItemModel(key);
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("tooltip-style")) {
            try {
                NamespacedKey key = NamespacedKey.fromString(cfg.getString("tooltip-style"));
                if (key != null) meta.setTooltipStyle(key);
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("max-stack-size")) {
            try {
                meta.setMaxStackSize(Math.clamp(cfg.getInt("max-stack-size"), 1, 99));
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("rarity")) {
            try {
                meta.setRarity(ItemRarity.valueOf(cfg.getString("rarity").toUpperCase()));
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("hide-tooltip")) {
            try {
                meta.setHideTooltip(cfg.getBoolean("hide-tooltip"));
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("glowing") || cfg.contains("glint-override")) {
            try {
                meta.setEnchantmentGlintOverride(cfg.getBoolean("glowing", cfg.getBoolean("glint-override")));
            } catch (Throwable ignored) {
                if (cfg.getBoolean("glowing")) {
                    meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                    meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                }
            }
        }

        if (cfg.contains("glider")) {
            try {
                meta.setGlider(cfg.getBoolean("glider"));
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("enchantable")) {
            try {
                meta.setEnchantable(cfg.getInt("enchantable"));
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("unbreakable")) meta.setUnbreakable(cfg.getBoolean("unbreakable"));

        if (cfg.contains("use-cooldown")) {
            try {
                UseCooldownComponent cd = meta.getUseCooldown();
                cd.setCooldownSeconds((float) cfg.getDouble("use-cooldown.seconds", 1.0));
                if (cfg.contains("use-cooldown.group")) {
                    cd.setCooldownGroup(NamespacedKey.fromString(cfg.getString("use-cooldown.group")));
                }
                meta.setUseCooldown(cd);
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("food")) {
            try {
                FoodComponent food = meta.getFood();
                food.setNutrition(cfg.getInt("food.nutrition", 1));
                food.setSaturation((float) cfg.getDouble("food.saturation", 1.0));
                food.setCanAlwaysEat(cfg.getBoolean("food.can-always-eat", false));
                meta.setFood(food);
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("equippable")) {
            try {
                EquippableComponent eq = meta.getEquippable();
                eq.setSlot(EquipmentSlot.valueOf(cfg.getString("equippable.slot", "HEAD").toUpperCase()));
                meta.setEquippable(eq);
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("jukebox-playable")) {
            try {
                JukeboxPlayableComponent jp = meta.getJukeboxPlayable();
                NamespacedKey songKey = NamespacedKey.fromString(cfg.getString("jukebox-playable.song"));
                if (songKey != null) {
                    jp.setSongKey(songKey);
                    meta.setJukeboxPlayable(jp);
                }
            } catch (Throwable ignored) {
            }
        }

        if (cfg.contains("flags")) {
            for (String flag : cfg.getStringList("flags")) {
                try {
                    meta.addItemFlags(ItemFlag.valueOf(flag.toUpperCase()));
                } catch (Exception ignored) {
                }
            }
        }

        if (cfg.contains("enchants")) {
            ConfigurationSection enchSec = cfg.getConfigurationSection("enchants");
            if (enchSec != null) {
                for (String key : enchSec.getKeys(false)) {
                    NamespacedKey nsKey = NamespacedKey.fromString(key.toLowerCase());
                    if (nsKey != null) {
                        Enchantment enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(nsKey);
                        if (enchantment != null) meta.addEnchant(enchantment, enchSec.getInt(key), true);
                    }
                }
            }
        }

        if (cfg.contains("attributes")) {
            ConfigurationSection attrSec = cfg.getConfigurationSection("attributes");
            if (attrSec != null) {
                for (String key : attrSec.getKeys(false)) {
                    NamespacedKey nsKey = NamespacedKey.fromString(key.toLowerCase());
                    if (nsKey != null) {
                        Attribute attribute = RegistryAccess.registryAccess().getRegistry(RegistryKey.ATTRIBUTE).get(nsKey);
                        if (attribute != null) {
                            String attrPath = "attributes." + key;
                            double amt = cfg.getDouble(attrPath + ".amount", 0.0);
                            String opStr = cfg.getString(attrPath + ".operation", "ADD_NUMBER");
                            String slotStr = cfg.getString(attrPath + ".slot", "ANY");
                            try {
                                AttributeModifier.Operation op = AttributeModifier.Operation.valueOf(opStr.toUpperCase());
                                EquipmentSlotGroup slotGroup = EquipmentSlotGroup.getByName(slotStr.toLowerCase());
                                if (slotGroup == null) slotGroup = EquipmentSlotGroup.ANY;
                                NamespacedKey modKey = new NamespacedKey(plugin, UUID.randomUUID().toString());
                                AttributeModifier modifier = new AttributeModifier(modKey, amt, op, slotGroup);
                                meta.addAttributeModifier(attribute, modifier);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to parse attribute modifier for " + key);
                            }
                        }
                    }
                }
            }
        }

        if (cfg.contains("damage") && meta instanceof Damageable dmgMeta) {
            dmgMeta.setDamage(cfg.getInt("damage"));
        }

        return this;
    }

    public ItemStack build() {
        if (meta != null) {
            item.setItemMeta(meta);
        }
        return item;
    }
}
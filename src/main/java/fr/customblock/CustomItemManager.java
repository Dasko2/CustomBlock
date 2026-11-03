package fr.customblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.inventory.meta.components.EquippableComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Objets personnalisés (outils, armes, armures, objets simples) définis dans la section "items" de config.yml.
 */
public final class CustomItemManager {

    /**
     * @param material     matériau de base de l'objet (ex. DIAMOND_SWORD, NETHERITE_HELMET, PAPER)
     * @param texture      icône PNG (inventaire, objet au sol) dans plugins/CustomBlock/textures/ (peut être null)
     * @param heldTexture  texture PNG de l'objet tenu en main (null = même texture que l'icône)
     * @param armorTexture préfixe des textures portées : <préfixe>_layer_1.png et <préfixe>_layer_2.png (peut être null)
     * @param durability   durabilité maximale (0 = celle du matériau)
     * @param attributes   attributs, clés normalisées (ex. attack_damage, armor, max_health)
     */
    public record ItemDefinition(
            String id,
            Material material,
            String name,
            List<String> lore,
            String texture,
            String heldTexture,
            String armorTexture,
            int durability,
            boolean unbreakable,
            Map<String, Double> attributes,
            int customModelData
    ) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_.-]+");

    private final Main plugin;
    private final Map<String, ItemDefinition> definitions = new LinkedHashMap<>();
    private final NamespacedKey idKey;

    public CustomItemManager(Main plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "item_id");
    }

    /**
     * (Re)charge les objets depuis la configuration.
     *
     * @param reservedIds identifiants déjà pris par des blocs (les IDs doivent être uniques)
     */
    public void load(Collection<String> reservedIds) {
        definitions.clear();

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("items");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;

            String id = key.toLowerCase(Locale.ROOT);
            if (!VALID_ID.matcher(id).matches()) {
                plugin.getLogger().warning("[" + key + "] ID invalide (lettres minuscules, chiffres, _ . - uniquement) : ignoré.");
                continue;
            }
            if (reservedIds.contains(id)) {
                plugin.getLogger().warning("[" + id + "] cet ID est déjà utilisé par un bloc : objet ignoré.");
                continue;
            }

            Material material = Material.matchMaterial(s.getString("material", ""));
            if (material == null || material.isAir() || !material.isItem()) {
                plugin.getLogger().warning("[" + id + "] material invalide : objet ignoré.");
                continue;
            }

            String heldTexture = blankToNull(s.getString("held-texture"));
            String texture = blankToNull(s.getString("texture"));
            if (texture == null) texture = heldTexture; // seule la texture en main est fournie : elle sert aussi d'icône
            String armorTexture = blankToNull(s.getString("armor-texture"));
            if (armorTexture != null) {
                if (!isArmorSlot(material.getEquipmentSlot())) {
                    plugin.getLogger().warning("[" + id + "] armor-texture ignoré : " + material.name() + " n'est pas une pièce d'armure.");
                    armorTexture = null;
                } else if (!VALID_ID.matcher(armorTexture.toLowerCase(Locale.ROOT)).matches()) {
                    plugin.getLogger().warning("[" + id + "] armor-texture invalide (lettres minuscules, chiffres, _ . - uniquement) : ignoré.");
                    armorTexture = null;
                } else {
                    armorTexture = armorTexture.toLowerCase(Locale.ROOT);
                }
            }

            Map<String, Double> attributes = new LinkedHashMap<>();
            ConfigurationSection attrSection = s.getConfigurationSection("attributes");
            if (attrSection != null) {
                for (String attrKey : attrSection.getKeys(false)) {
                    String normalized = attrKey.toLowerCase(Locale.ROOT).replace('-', '_');
                    if (Registry.ATTRIBUTE.get(NamespacedKey.minecraft(normalized)) == null) {
                        plugin.getLogger().warning("[" + id + "] attribut inconnu : " + attrKey);
                        continue;
                    }
                    attributes.put(normalized, attrSection.getDouble(attrKey));
                }
            }

            definitions.put(id, new ItemDefinition(
                    id,
                    material,
                    s.getString("name", "<white>" + id),
                    s.getStringList("lore"),
                    texture,
                    heldTexture,
                    armorTexture,
                    Math.max(0, s.getInt("durability", 0)),
                    s.getBoolean("unbreakable", false),
                    attributes,
                    s.getInt("custom-model-data", 0)));
        }
    }

    public Map<String, ItemDefinition> getDefinitions() {
        return Collections.unmodifiableMap(definitions);
    }

    public ItemDefinition getDefinition(String id) {
        return id == null ? null : definitions.get(id.toLowerCase(Locale.ROOT));
    }

    /** Construit l'objet : nom, lore, icône, durabilité, attributs et texture d'armure. */
    public ItemStack createItem(ItemDefinition def, int amount) {
        ItemStack item = new ItemStack(def.material(), amount);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(MM.deserialize(def.name()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));

        if (!def.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : def.lore()) {
                lore.add(MM.deserialize(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(lore);
        }

        if (def.texture() != null) {
            meta.setItemModel(new NamespacedKey(plugin, def.id()));
        }

        if (def.durability() > 0 && meta instanceof Damageable damageable) {
            damageable.setMaxDamage(def.durability());
        }
        if (def.unbreakable()) {
            meta.setUnbreakable(true);
        }

        // Les attributs définis ici remplacent ceux du matériau d'origine (précisez dégâts ET vitesse d'attaque, etc.)
        EquipmentSlotGroup group = groupFor(def.material().getEquipmentSlot());
        for (Map.Entry<String, Double> entry : def.attributes().entrySet()) {
            Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(entry.getKey()));
            if (attribute == null) continue;

            double value = entry.getValue();
            // Le joueur possède 1 de dégâts et 4 de vitesse d'attaque de base : la config indique le total affiché.
            if (entry.getKey().equals("attack_damage")) value -= 1.0;
            if (entry.getKey().equals("attack_speed")) value -= 4.0;

            meta.addAttributeModifier(attribute, new AttributeModifier(
                    new NamespacedKey(plugin, def.id() + "_" + entry.getKey()),
                    value,
                    AttributeModifier.Operation.ADD_NUMBER,
                    group));
        }

        if (def.armorTexture() != null) {
            EquippableComponent equippable = meta.getEquippable();
            equippable.setSlot(def.material().getEquipmentSlot());
            equippable.setModel(new NamespacedKey(plugin, def.armorTexture()));
            meta.setEquippable(equippable);
        }

        if (def.customModelData() != 0) {
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setFloats(List.of((float) def.customModelData()));
            meta.setCustomModelDataComponent(component);
        }

        meta.getPersistentDataContainer().set(idKey, org.bukkit.persistence.PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }

    private static boolean isArmorSlot(EquipmentSlot slot) {
        return slot == EquipmentSlot.HEAD || slot == EquipmentSlot.CHEST
                || slot == EquipmentSlot.LEGS || slot == EquipmentSlot.FEET;
    }

    private static EquipmentSlotGroup groupFor(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> EquipmentSlotGroup.HEAD;
            case CHEST -> EquipmentSlotGroup.CHEST;
            case LEGS -> EquipmentSlotGroup.LEGS;
            case FEET -> EquipmentSlotGroup.FEET;
            default -> EquipmentSlotGroup.MAINHAND;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}

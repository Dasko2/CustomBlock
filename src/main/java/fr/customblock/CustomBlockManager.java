package fr.customblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Gestionnaire générique : tous les blocs sont définis dans config.yml.
 * Aucune classe par bloc n'est nécessaire.
 */
public final class CustomBlockManager {

    /**
     * Définition immuable d'un bloc, construite depuis la configuration.
     *
     * @param texture nom du fichier PNG dans plugins/CustomBlock/textures/ (peut être null)
     */
    public record BlockDefinition(
            String id,
            Material baseMaterial,
            Material placedMaterial,
            String name,
            List<String> lore,
            String texture,
            int customModelData,
            boolean dropSelf
    ) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_.-]+");

    private final Main plugin;
    private final NamespacedKey idKey;
    private final Map<String, BlockDefinition> definitions = new LinkedHashMap<>();
    private final Set<Material> trackedMaterials = EnumSet.noneOf(Material.class);

    public CustomBlockManager(Main plugin) {
        this.plugin = plugin;
        this.idKey = new NamespacedKey(plugin, "block_id");
    }

    /** (Re)charge toutes les définitions depuis la configuration. */
    public void load() {
        definitions.clear();
        trackedMaterials.clear();

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("blocks");
        if (section == null) {
            plugin.getLogger().warning("Section 'blocks' absente de config.yml.");
            return;
        }

        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) continue;

            String id = key.toLowerCase(Locale.ROOT);
            if (!VALID_ID.matcher(id).matches()) {
                plugin.getLogger().warning("[" + key + "] ID invalide (lettres minuscules, chiffres, _ . - uniquement) : ignoré.");
                continue;
            }

            Material base = Material.matchMaterial(s.getString("base-material", "STONE"));
            if (base == null || !base.isBlock() || base.isAir() || !base.isItem()) {
                plugin.getLogger().warning("[" + id + "] base-material invalide (doit être un matériau de bloc) : ignoré.");
                continue;
            }

            Material placed = base;
            String placedRaw = s.getString("placed-material");
            if (placedRaw != null) {
                Material parsed = Material.matchMaterial(placedRaw);
                if (parsed == null || !parsed.isBlock() || parsed.isAir()) {
                    plugin.getLogger().warning("[" + id + "] placed-material invalide : base-material utilisé à la place.");
                } else {
                    placed = parsed;
                }
            }

            String name = s.getString("name", "<white>" + id);
            List<String> lore = s.getStringList("lore");
            String texture = s.getString("texture");
            if (texture != null && texture.isBlank()) texture = null;
            int cmd = s.getInt("custom-model-data", 0);
            boolean dropSelf = s.getBoolean("drop-self", true);

            definitions.put(id, new BlockDefinition(id, base, placed, name, lore, texture, cmd, dropSelf));
            trackedMaterials.add(placed);
        }
    }

    public Map<String, BlockDefinition> getDefinitions() {
        return Collections.unmodifiableMap(definitions);
    }

    public BlockDefinition getDefinition(String id) {
        return id == null ? null : definitions.get(id.toLowerCase(Locale.ROOT));
    }

    /** Retourne la définition correspondant à un item, ou null si ce n'est pas un bloc custom. */
    public BlockDefinition getDefinition(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        return getDefinition(id);
    }

    public boolean isTracked(Material material) {
        return trackedMaterials.contains(material);
    }

    /** Construit l'item d'un bloc : nom MiniMessage, lore, modèle du resource pack et identifiant caché. */
    public ItemStack createItem(BlockDefinition def, int amount) {
        ItemStack item = new ItemStack(def.baseMaterial(), amount);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(MM.deserialize(def.name()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));

        if (!def.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : def.lore()) {
                lore.add(MM.deserialize(line).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            }
            meta.lore(lore);
        }

        // Modèle généré automatiquement dans le resource pack : <namespace>:<id>
        if (def.texture() != null) {
            meta.setItemModel(new NamespacedKey(plugin, def.id()));
        }

        if (def.customModelData() != 0) {
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setFloats(List.of((float) def.customModelData()));
            meta.setCustomModelDataComponent(component);
        }

        meta.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }

    /** Pose le bloc physique (hitbox) et l'entité d'affichage texturée. */
    public void placeBlock(Block block, BlockDefinition def) {
        block.setType(def.placedMaterial(), false);

        ItemStack displayItem = createItem(def, 1);
        Location center = block.getLocation().add(0.5, 0.5, 0.5);

        block.getWorld().spawn(center, ItemDisplay.class, display -> {
            display.setItemStack(displayItem);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            // Léger surdimensionnement pour masquer le bloc de base (évite le z-fighting)
            display.setTransformation(new Transformation(
                    new Vector3f(),
                    new AxisAngle4f(),
                    new Vector3f(1.002f, 1.002f, 1.002f),
                    new AxisAngle4f()));
            display.setPersistent(true);
            display.setInvulnerable(true);
            display.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, def.id());
        });
    }

    /** Cherche l'entité d'affichage d'un bloc custom, ou null si ce n'est pas un bloc custom. */
    public ItemDisplay findDisplay(Block block) {
        if (!isTracked(block.getType())) return null;

        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (ItemDisplay display : block.getWorld().getNearbyEntitiesByType(ItemDisplay.class, center, 0.25)) {
            PersistentDataContainer pdc = display.getPersistentDataContainer();
            if (pdc.has(idKey, PersistentDataType.STRING)) {
                return display;
            }
        }
        return null;
    }

    public boolean isCustomBlock(Block block) {
        return findDisplay(block) != null;
    }

    /**
     * Retire l'entité d'affichage d'un bloc custom et fait éventuellement tomber l'item.
     * Ne modifie pas le bloc lui-même.
     *
     * @return true si le bloc était un bloc custom
     */
    public boolean removeBlock(Block block, boolean dropItem) {
        ItemDisplay display = findDisplay(block);
        if (display == null) return false;

        String id = display.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
        BlockDefinition def = getDefinition(id);
        display.remove();

        if (dropItem && def != null && def.dropSelf()) {
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            block.getWorld().dropItemNaturally(center, createItem(def, 1));
        }
        return true;
    }
}

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
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Gestionnaire générique : tous les blocs sont définis dans config.yml.
 * Aucune classe par bloc n'est nécessaire.
 */
public final class CustomBlockManager {

    /**
     * Un drop : un objet custom (ID) ou vanilla (matériau), une quantité (min à max) et une chance en %.
     */
    public record DropDefinition(String item, int min, int max, double chance) {
    }

    /**
     * Définition immuable d'un bloc, construite depuis la configuration.
     *
     * @param textures  fichier PNG de chaque face (up, down, north, south, east, west) ; vide = pas de texture
     * @param hardness  dureté (null = celle du matériau posé, 0 = instantané, négatif = incassable)
     * @param tool      outil requis pour obtenir les drops (PICKAXE, AXE, SHOVEL, HOE, SWORD) ou null
     * @param minTier   niveau minimal de l'outil (0 bois/or, 1 pierre/cuivre, 2 fer, 3 diamant, 4 netherite) ou -1
     * @param drops     objets lâchés à la casse
     */
    public record BlockDefinition(
            String id,
            Material baseMaterial,
            Material placedMaterial,
            String name,
            List<String> lore,
            Map<String, String> textures,
            int customModelData,
            Double hardness,
            String tool,
            int minTier,
            List<DropDefinition> drops
    ) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_.-]+");
    private static final Set<String> TOOLS = Set.of("PICKAXE", "AXE", "SHOVEL", "HOE", "SWORD");

    private final Main plugin;
    private final CustomItemManager itemManager;
    private final NamespacedKey idKey;
    private final Map<String, BlockDefinition> definitions = new LinkedHashMap<>();
    private final Set<Material> trackedMaterials = EnumSet.noneOf(Material.class);

    public CustomBlockManager(Main plugin, CustomItemManager itemManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
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

            Double hardness = s.contains("hardness") ? s.getDouble("hardness") : null;
            if (hardness != null && hardness != 0 && placed.getHardness() <= 0) {
                plugin.getLogger().warning("[" + id + "] hardness ignorée : le matériau posé " + placed.name()
                        + " a une dureté fixe. Choisis un placed-material minable (STONE, OAK_PLANKS, DIRT...).");
                hardness = null;
            }

            String tool = null;
            String toolRaw = s.getString("tool");
            if (toolRaw != null && !toolRaw.isBlank() && !toolRaw.equalsIgnoreCase("NONE")) {
                String normalized = toolRaw.trim().toUpperCase(Locale.ROOT);
                if (TOOLS.contains(normalized)) {
                    tool = normalized;
                } else {
                    plugin.getLogger().warning("[" + id + "] tool invalide '" + toolRaw + "' (PICKAXE, AXE, SHOVEL, HOE, SWORD ou NONE) : ignoré.");
                }
            }

            int minTier = -1;
            String tierRaw = s.getString("min-tier");
            if (tierRaw != null && !tierRaw.isBlank() && !tierRaw.equalsIgnoreCase("NONE")) {
                minTier = parseTier(tierRaw);
                if (minTier < 0) {
                    plugin.getLogger().warning("[" + id + "] min-tier invalide '" + tierRaw + "' (WOOD, STONE, IRON, DIAMOND, NETHERITE ou NONE) : ignoré.");
                }
            }

            definitions.put(id, new BlockDefinition(
                    id,
                    base,
                    placed,
                    s.getString("name", "<white>" + id),
                    s.getStringList("lore"),
                    parseFaces(id, s),
                    s.getInt("custom-model-data", 0),
                    hardness,
                    tool,
                    minTier,
                    parseDrops(id, s)));
            trackedMaterials.add(placed);
        }
    }

    /** Vérifie, une fois blocs et objets chargés, que chaque drop pointe vers un objet existant. */
    public void validateDrops() {
        for (BlockDefinition def : definitions.values()) {
            for (DropDefinition drop : def.drops()) {
                if (createStack(drop.item(), 1) == null) {
                    plugin.getLogger().warning("[" + def.id() + "] drop inconnu : '" + drop.item()
                            + "' (ni objet custom, ni matériau vanilla).");
                }
            }
        }
    }

    private Map<String, String> parseFaces(String id, ConfigurationSection s) {
        ConfigurationSection t = s.getConfigurationSection("textures");
        String all = first(t == null ? null : t.getString("all"), s.getString("texture"));

        Map<String, String> faces = new LinkedHashMap<>();
        faces.put("up", first(get(t, "up"), get(t, "top"), get(t, "end"), all));
        faces.put("down", first(get(t, "down"), get(t, "bottom"), get(t, "end"), all));
        faces.put("north", first(get(t, "north"), get(t, "side"), all));
        faces.put("south", first(get(t, "south"), get(t, "side"), all));
        faces.put("east", first(get(t, "east"), get(t, "side"), all));
        faces.put("west", first(get(t, "west"), get(t, "side"), all));

        boolean any = faces.values().stream().anyMatch(v -> v != null);
        boolean complete = faces.values().stream().allMatch(v -> v != null);
        if (!any) return Map.of();
        if (!complete) {
            plugin.getLogger().warning("[" + id + "] textures incomplètes : il faut les 6 faces (all, ou top/bottom/side, ou up/down/north/south/east/west). Texture ignorée.");
            return Map.of();
        }
        return Collections.unmodifiableMap(faces);
    }

    private static String get(ConfigurationSection section, String key) {
        return section == null ? null : section.getString(key);
    }

    private static String first(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private List<DropDefinition> parseDrops(String id, ConfigurationSection s) {
        List<DropDefinition> drops = new ArrayList<>();

        if (!s.contains("drops")) {
            if (s.getBoolean("drop-self", true)) {
                drops.add(new DropDefinition(id, 1, 1, 100.0));
            }
            return drops;
        }

        for (Map<?, ?> entry : s.getMapList("drops")) {
            Object item = entry.get("item");
            if (item == null) {
                plugin.getLogger().warning("[" + id + "] un drop n'a pas de champ 'item' : ignoré.");
                continue;
            }
            int[] range = parseRange(entry.containsKey("amount") ? String.valueOf(entry.get("amount")) : "1");
            double chance = 100.0;
            Object chanceRaw = entry.get("chance");
            if (chanceRaw instanceof Number number) {
                chance = number.doubleValue();
            } else if (chanceRaw != null) {
                try {
                    chance = Double.parseDouble(String.valueOf(chanceRaw));
                } catch (NumberFormatException ignored) {
                    // garde 100 %
                }
            }
            drops.add(new DropDefinition(String.valueOf(item).trim(), range[0], range[1], chance));
        }
        return drops;
    }

    private static int[] parseRange(String raw) {
        String value = raw.trim();
        try {
            int dash = value.indexOf('-', 1);
            if (dash > 0) {
                int min = Integer.parseInt(value.substring(0, dash).trim());
                int max = Integer.parseInt(value.substring(dash + 1).trim());
                return new int[]{min, Math.max(min, max)};
            }
            int single = Integer.parseInt(value);
            return new int[]{single, single};
        } catch (NumberFormatException ex) {
            return new int[]{1, 1};
        }
    }

    private static int parseTier(String raw) {
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "WOOD", "WOODEN", "GOLD", "GOLDEN" -> 0;
            case "STONE", "COPPER" -> 1;
            case "IRON" -> 2;
            case "DIAMOND" -> 3;
            case "NETHERITE" -> 4;
            default -> -1;
        };
    }

    private static int toolTier(String materialName) {
        int underscore = materialName.indexOf('_');
        return underscore < 0 ? -1 : parseTier(materialName.substring(0, underscore));
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

    /** Retourne la définition du bloc custom posé à cet endroit, ou null. */
    public BlockDefinition getDefinitionAt(Block block) {
        ItemDisplay display = findDisplay(block);
        if (display == null) return null;
        return getDefinition(display.getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
    }

    public boolean isTracked(Material material) {
        return trackedMaterials.contains(material);
    }

    /** true si l'ID correspond à un bloc ou un objet custom. */
    public boolean isCustomId(String key) {
        return getDefinition(key) != null || itemManager.getDefinition(key) != null;
    }

    /**
     * Crée un item à partir d'une clé : ID de bloc custom, ID d'objet custom, ou matériau vanilla.
     *
     * @return l'item, ou null si la clé est inconnue
     */
    public ItemStack createStack(String key, int amount) {
        BlockDefinition block = getDefinition(key);
        if (block != null) return createItem(block, amount);

        CustomItemManager.ItemDefinition item = itemManager.getDefinition(key);
        if (item != null) return itemManager.createItem(item, amount);

        Material material = Material.matchMaterial(key);
        if (material != null && material.isItem() && !material.isAir()) {
            return new ItemStack(material, amount);
        }
        return null;
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
        if (!def.textures().isEmpty()) {
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

    /** true si l'outil respecte le type et le niveau demandés par le bloc (null = aucune vérification). */
    private boolean toolAllows(BlockDefinition def, ItemStack tool) {
        if (tool == null) return true;
        if (def.tool() == null && def.minTier() < 0) return true;

        String name = tool.getType().name();
        if (def.tool() != null && !name.endsWith("_" + def.tool())) return false;
        return def.minTier() < 0 || toolTier(name) >= def.minTier();
    }

    /**
     * Retire l'entité d'affichage d'un bloc custom et fait éventuellement tomber ses drops.
     * Ne modifie pas le bloc lui-même.
     *
     * @param tool outil utilisé pour casser (null = pas de vérification, ex. explosion)
     * @return true si le bloc était un bloc custom
     */
    public boolean removeBlock(Block block, ItemStack tool, boolean dropItems) {
        ItemDisplay display = findDisplay(block);
        if (display == null) return false;

        BlockDefinition def = getDefinition(display.getPersistentDataContainer().get(idKey, PersistentDataType.STRING));
        display.remove();

        if (dropItems && def != null && toolAllows(def, tool)) {
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            ThreadLocalRandom random = ThreadLocalRandom.current();

            for (DropDefinition drop : def.drops()) {
                if (drop.chance() < 100.0 && random.nextDouble() * 100.0 >= drop.chance()) continue;

                int amount = drop.min() == drop.max() ? drop.min() : random.nextInt(drop.min(), drop.max() + 1);
                while (amount > 0) {
                    ItemStack stack = createStack(drop.item(), 1);
                    if (stack == null) break;
                    int part = Math.min(amount, stack.getMaxStackSize());
                    stack.setAmount(part);
                    block.getWorld().dropItemNaturally(center, stack);
                    amount -= part;
                }
            }
        }
        return true;
    }
}

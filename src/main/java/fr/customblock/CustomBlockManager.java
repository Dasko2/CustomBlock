package fr.customblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Gestionnaire générique : tous les blocs sont définis dans config.yml.
 *
 * Un bloc posé est un vrai bloc Minecraft (état de note_block) dont le modèle est remplacé par le
 * resource pack : l'éclairage, la night vision et le fullbright agissent donc exactement comme sur
 * un bloc vanilla, sans aucune entité.
 */
public final class CustomBlockManager {

    /**
     * Un drop : un objet custom (ID, y compris le bloc lui-même) ou vanilla (matériau), une quantité (min à max)
     * et une chance en %.
     *
     * @param silkTouch true = seulement avec Toucher de soie, false = seulement sans, null = dans tous les cas
     */
    public record DropDefinition(String item, int min, int max, double chance, Boolean silkTouch) {
    }

    /**
     * Définition immuable d'un bloc, construite depuis la configuration.
     *
     * @param textures fichier PNG de chaque face (up, down, north, south, east, west)
     * @param axis     true = tronc : il se pose dans le sens de la face cliquée (3 états)
     * @param hardness dureté (null = celle d'un bloc-note, 0 = instantané, négatif = incassable)
     * @param tool     outil requis pour obtenir les drops (PICKAXE, AXE, SHOVEL, HOE, SWORD) ou null
     * @param minTier  niveau minimal de l'outil (0 bois/or, 1 pierre/cuivre, 2 fer, 3 diamant, 4 netherite) ou -1
     * @param drops    objets lâchés à la casse
     * @param shape    CUBE, SLAB (dalle : collision de dalle de chêne pétrifié + modèle affiché) ou CROSS (plante)
     * @param facing   true = le bloc se tourne face au joueur (4 états)
     */
    public record BlockDefinition(
            String id,
            Material baseMaterial,
            String name,
            List<String> lore,
            Map<String, String> textures,
            boolean axis,
            int customModelData,
            Double hardness,
            String tool,
            int minTier,
            List<DropDefinition> drops,
            String shape,
            boolean facing
    ) {
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9_.-]+");
    private static final Set<String> TOOLS = Set.of("PICKAXE", "AXE", "SHOVEL", "HOE", "SWORD");

    private final Main plugin;
    private final CustomItemManager itemManager;
    private final BlockStateRegistry registry;
    private final Map<String, BlockDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, BlockData> dataCache = new HashMap<>();

    public CustomBlockManager(Main plugin, CustomItemManager itemManager) {
        this.plugin = plugin;
        this.itemManager = itemManager;
        this.registry = new BlockStateRegistry(plugin);
    }

    public BlockStateRegistry getRegistry() {
        return registry;
    }

    /** (Re)charge toutes les définitions depuis la configuration. */
    public void load() {
        definitions.clear();

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

            if (s.contains("placed-material")) {
                plugin.getLogger().warning("[" + id + "] 'placed-material' n'est plus utilisé : les blocs sont de vrais états de bloc. Tu peux le retirer.");
            }

            Map<String, String> textures = parseFaces(id, s);
            if (textures.isEmpty()) {
                plugin.getLogger().warning("[" + id + "] bloc ignoré : il lui faut une texture (texture, ou textures avec les 6 faces).");
                continue;
            }

            Double hardness = s.contains("hardness") ? s.getDouble("hardness") : null;

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

            String orientation = s.getString("orientation", "NONE");
            boolean axis = "AXIS".equalsIgnoreCase(orientation);
            boolean facing = "FACING".equalsIgnoreCase(orientation);

            String shape = s.getString("shape", "CUBE").trim().toUpperCase(Locale.ROOT);
            if (shape.equals("STAIRS")) {
                plugin.getLogger().warning("[" + id + "] shape STAIRS non supportée (aucun bloc vanilla inobtenable n'a la forme d'un escalier) : bloc ignoré.");
                continue;
            }
            if (!Set.of("CUBE", "SLAB", "CROSS").contains(shape)) {
                plugin.getLogger().warning("[" + id + "] shape invalide '" + shape + "' (CUBE, SLAB ou CROSS) : CUBE utilisé.");
                shape = "CUBE";
            }

            if (shape.equals("CROSS")) {
                // Plante en croix : pas de bloc, un affichage + une boîte de clic posés par le plugin
                base = Material.PAPER;
                axis = false;
                facing = false;
            } else if (shape.equals("SLAB")) {
                // Dalle : collision de dalle de chêne pétrifié (introuvable en survie, rendue invisible par le pack)
                // + modèle affiché par une entité
                base = Material.PETRIFIED_OAK_SLAB;
                axis = false;
                facing = false;
            } else if (registry.assign(id, axis ? 3 : facing ? 4 : 1) == null) {
                plugin.getLogger().severe("[" + id + "] bloc ignoré : plus d'états disponibles.");
                continue;
            }

            definitions.put(id, new BlockDefinition(
                    id,
                    base,
                    s.getString("name", "<white>" + id),
                    s.getStringList("lore"),
                    textures,
                    axis,
                    s.getInt("custom-model-data", 0),
                    hardness,
                    tool,
                    minTier,
                    parseDrops(id, s),
                    shape,
                    facing));
        }

        registry.save();
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
            plugin.getLogger().warning("[" + id + "] textures incomplètes : il faut les 6 faces (all, ou top/bottom/side, ou up/down/north/south/east/west).");
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
                drops.add(new DropDefinition(id, 1, 1, 100.0, null));
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
            Boolean silk = null;
            Object silkRaw = entry.get("silk-touch");
            if (silkRaw instanceof Boolean flag) {
                silk = flag;
            } else if (silkRaw != null) {
                String text = String.valueOf(silkRaw).trim().toLowerCase(Locale.ROOT);
                if (text.equals("true") || text.equals("false")) silk = Boolean.parseBoolean(text);
            }
            drops.add(new DropDefinition(String.valueOf(item).trim(), range[0], range[1], chance, silk));
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

    static int toolTier(String materialName) {
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
        String id = item.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "block_id"), PersistentDataType.STRING);
        return getDefinition(id);
    }

    // ------------------------------------------------------------------ blocs posés

    /** Définition du bloc custom posé à cet endroit, ou null (bloc vanilla). */
    public BlockDefinition getDefinitionAt(Block block) {
        Material type = block.getType();
        if (type == Material.NOTE_BLOCK) return getDefinitionOf(block.getBlockData());
        if (type == Material.PETRIFIED_OAK_SLAB) {
            String id = slabIdAt(block);
            return id == null ? null : definitions.get(id);
        }
        return null;
    }

    public BlockDefinition getDefinitionOf(BlockData data) {
        if (data.getMaterial() != Material.NOTE_BLOCK) return null;
        BlockStateRegistry.Entry entry = registry.find(data.getAsString());
        return entry == null ? null : definitions.get(entry.id());
    }

    public NamespacedKey slabKey() {
        return new NamespacedKey(plugin, "slab_id");
    }

    /** ID de la dalle custom posée ici (lu sur l'affichage de la case), ou null. */
    public String slabIdAt(Block block) {
        for (org.bukkit.entity.Entity entity : slabEntities(block)) {
            String id = entity.getPersistentDataContainer().get(slabKey(), PersistentDataType.STRING);
            if (id != null) return id;
        }
        return null;
    }

    public List<org.bukkit.entity.Entity> slabEntities(Block block) {
        List<org.bukkit.entity.Entity> result = new ArrayList<>();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (org.bukkit.entity.Entity entity : block.getWorld().getNearbyEntities(center, 0.3, 0.3, 0.3)) {
            if (entity.getPersistentDataContainer().has(slabKey(), PersistentDataType.STRING)) {
                result.add(entity);
            }
        }
        return result;
    }

    public boolean isCustomBlock(Block block) {
        return getDefinitionAt(block) != null;
    }

    /**
     * Données du bloc à poser.
     *
     * @param variant 0 = unique (ou axe Y), 1 = axe X, 2 = axe Z
     * @return les données, ou null si l'ID est inconnu
     */
    public BlockData stateData(String id, int variant) {
        List<String> states = registry.statesOf(id);
        if (variant < 0 || variant >= states.size()) return null;
        return dataCache.computeIfAbsent(states.get(variant), Bukkit::createBlockData).clone();
    }

    /** Variante d'un tronc d'après la face sur laquelle il est posé : 0 = Y, 1 = X, 2 = Z. */
    public static int axisVariant(Block placed, Block against) {
        if (placed.getX() != against.getX()) return 1;
        if (placed.getZ() != against.getZ()) return 2;
        return 0;
    }

    /** Variante d'un bloc orienté d'après la direction du joueur : sa face avant (nord du modèle) lui fait face. */
    public static int facingVariant(org.bukkit.block.BlockFace playerFacing) {
        return switch (playerFacing) {
            case NORTH -> 2;
            case EAST -> 3;
            case WEST -> 1;
            default -> 0;
        };
    }

    /** Pose le bloc custom : le client le dessine avec le modèle du resource pack. */
    public void placeBlock(Block block, BlockDefinition def, int variant) {
        BlockData data = stateData(def.id(), (def.axis() || def.facing()) ? variant : 0);
        if (data != null) {
            block.setBlockData(data, false);
        }
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
     * Fait tomber les drops d'un bloc custom. Ne modifie pas le bloc lui-même.
     *
     * @param tool outil utilisé pour casser (null = pas de vérification, ex. explosion)
     * @return true si le bloc était un bloc custom
     */
    public boolean removeBlock(Block block, ItemStack tool, boolean dropItems) {
        BlockDefinition def = getDefinitionAt(block);
        if (def == null) return false;

        if (def.shape().equals("SLAB")) {
            // Le modèle affiché disparaît avec la dalle
            for (org.bukkit.entity.Entity entity : slabEntities(block)) entity.remove();
        }

        if (dropItems) {
            // Une dalle double lâche deux exemplaires, comme en vanilla
            int rounds = block.getBlockData() instanceof org.bukkit.block.data.type.Slab slab
                    && slab.getType() == org.bukkit.block.data.type.Slab.Type.DOUBLE ? 2 : 1;
            dropItems(def, block.getLocation().add(0.5, 0.5, 0.5), tool, rounds);
        }
        return true;
    }

    /**
     * Fait tomber les drops d'un bloc à un endroit : outil et niveau requis, Toucher de soie, chance et quantités.
     *
     * @param tool outil utilisé pour casser (null = pas de vérification, ex. explosion)
     */
    public void dropItems(BlockDefinition def, Location center, ItemStack tool, int rounds) {
        if (!toolAllows(def, tool)) return;

        ThreadLocalRandom random = ThreadLocalRandom.current();
        boolean silk = tool != null && tool.containsEnchantment(org.bukkit.enchantments.Enchantment.SILK_TOUCH);

        for (int round = 0; round < rounds; round++) {
            for (DropDefinition drop : def.drops()) {
                if (drop.silkTouch() != null && drop.silkTouch() != silk) continue;
                if (drop.chance() < 100.0 && random.nextDouble() * 100.0 >= drop.chance()) continue;

                int amount = drop.min() == drop.max() ? drop.min() : random.nextInt(drop.min(), drop.max() + 1);
                while (amount > 0) {
                    ItemStack stack = createStack(drop.item(), 1);
                    if (stack == null) break;
                    int part = Math.min(amount, stack.getMaxStackSize());
                    stack.setAmount(part);
                    center.getWorld().dropItemNaturally(center, stack);
                    amount -= part;
                }
            }
        }
    }

    // ------------------------------------------------------------------ objets

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
        meta.setItemModel(new NamespacedKey(plugin, def.id()));

        if (def.customModelData() != 0) {
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            component.setFloats(List.of((float) def.customModelData()));
            meta.setCustomModelDataComponent(component);
        }

        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "block_id"), PersistentDataType.STRING, def.id());
        item.setItemMeta(meta);
        return item;
    }
}

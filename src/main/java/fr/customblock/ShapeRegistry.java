package fr.customblock;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Les dalles et escaliers personnalisés sont de vrais blocs vanilla (collision, éclairage, forme des coins
 * et eau : tout est géré par Minecraft). Chaque bloc custom prend un matériau « réservé » parmi des escaliers
 * ou dalles peu utilisés ; le resource pack remplace l'apparence de ce matériau.
 *
 * Les attributions sont enregistrées dans generated/shape-materials.yml : ne jamais supprimer ce fichier.
 */
public final class ShapeRegistry {

    private static final List<String> STAIRS_BASES = List.of(
            "WAXED_CUT_COPPER", "WAXED_EXPOSED_CUT_COPPER", "WAXED_WEATHERED_CUT_COPPER", "WAXED_OXIDIZED_CUT_COPPER",
            "EXPOSED_CUT_COPPER", "WEATHERED_CUT_COPPER", "OXIDIZED_CUT_COPPER",
            "TUFF", "POLISHED_TUFF", "TUFF_BRICK");

    private final Main plugin;
    private final File file;
    private final Map<String, Material> idToMaterial = new LinkedHashMap<>();
    private final Map<Material, String> materialToId = new HashMap<>();

    public ShapeRegistry(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "generated/shape-materials.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String id : yaml.getKeys(false)) {
            Material material = Material.matchMaterial(yaml.getString(id, ""));
            if (material == null) continue;
            idToMaterial.put(id, material);
            materialToId.put(material, id);
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Material> entry : idToMaterial.entrySet()) {
            yaml.set(entry.getKey(), entry.getValue().name());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            yaml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Impossible d'enregistrer " + file.getPath() + " : " + ex.getMessage());
        }
    }

    /**
     * Matériau réservé à ce bloc (en réserve un nouveau si besoin).
     *
     * @return le matériau, ou null s'il n'en reste plus ou si l'ID était déjà attribué à l'autre forme
     */
    public Material assign(String id, boolean stairs) {
        String suffix = stairs ? "_STAIRS" : "_SLAB";
        Material existing = idToMaterial.get(id);
        if (existing != null) {
            if (existing.name().endsWith(suffix)) return existing;
            plugin.getLogger().warning("[" + id + "] ce bloc avait été créé avec une autre forme (" + existing.name()
                    + ") : change l'ID du bloc pour changer de forme.");
            return null;
        }
        for (String base : STAIRS_BASES) {
            Material candidate = Material.matchMaterial(base + suffix);
            if (candidate == null || materialToId.containsKey(candidate)) continue;
            idToMaterial.put(id, candidate);
            materialToId.put(candidate, id);
            return candidate;
        }
        return null;
    }

    /** ID du bloc custom qui utilise ce matériau, ou null. */
    public String idOf(Material material) {
        return materialToId.get(material);
    }

    public List<Material> allMaterials() {
        return new ArrayList<>(materialToId.keySet());
    }

    public static String describePool() {
        return STAIRS_BASES.size() + " escaliers et " + STAIRS_BASES.size() + " dalles";
    }

    static String lower(Material m) {
        return m.name().toLowerCase(Locale.ROOT);
    }
}

package fr.customblock;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chaque bloc personnalisé occupe un ou plusieurs états de note_block (instrument / note / powered).
 * Le resource pack remplace le modèle de ces états : le client dessine alors un vrai bloc,
 * avec l'éclairage, l'occlusion ambiante, la night vision et le fullbright de Minecraft.
 *
 * Les attributions sont enregistrées dans generated/block-states.yml et ne changent jamais :
 * un bloc déjà posé garde la même apparence après un reload ou un redémarrage.
 */
public final class BlockStateRegistry {

    /** Une attribution : l'ID du bloc et son numéro de variante (0 = unique, ou axe Y pour les troncs). */
    public record Entry(String id, int variant) {
    }

    /** Instruments utilisés pour l'identité des blocs (ceux sans tête de mob). */
    private static final List<String> POOL_INSTRUMENTS = List.of(
            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar",
            "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling");

    /** Tous les instruments existants : le pack doit définir un modèle pour chaque état. */
    public static final List<String> ALL_INSTRUMENTS = List.of(
            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar",
            "chime", "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling",
            "zombie", "skeleton", "creeper", "dragon", "wither_skeleton", "piglin", "custom_head");

    private final Main plugin;
    private final File file;
    private final List<String> pool = new ArrayList<>();
    private final Map<String, List<String>> idToStates = new LinkedHashMap<>();
    private final Map<String, Entry> stateToEntry = new HashMap<>();

    public BlockStateRegistry(Main plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "generated/block-states.yml");
        buildPool();
        load();
    }

    /**
     * Les notes 1 à 24 sont réservées aux blocs custom : un bloc-note vanilla garde la note 0
     * (l'accordage est désactivé), il ne peut donc jamais prendre l'apparence d'un bloc custom.
     */
    private void buildPool() {
        for (int note = 1; note <= 24; note++) {
            for (String instrument : POOL_INSTRUMENTS) {
                for (boolean powered : new boolean[]{false, true}) {
                    String raw = "minecraft:note_block[instrument=" + instrument + ",note=" + note + ",powered=" + powered + "]";
                    pool.add(Bukkit.createBlockData(raw).getAsString());
                }
            }
        }
    }

    private void load() {
        if (!file.isFile()) return;

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator('/');
        try {
            yaml.load(file);
        } catch (Exception ex) {
            plugin.getLogger().severe("Lecture de block-states.yml impossible : " + ex.getMessage()
                    + ". Les blocs déjà posés risquent de changer d'apparence : restaure ce fichier avant de continuer.");
            return;
        }

        for (String id : yaml.getKeys(false)) {
            List<String> states = new ArrayList<>(yaml.getStringList(id));
            idToStates.put(id, states);
            for (int i = 0; i < states.size(); i++) {
                stateToEntry.put(states.get(i), new Entry(id, i));
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().pathSeparator('/');
        for (Map.Entry<String, List<String>> entry : idToStates.entrySet()) {
            yaml.set(entry.getKey(), entry.getValue());
        }
        try {
            File parent = file.getParentFile();
            if (parent != null) parent.mkdirs();
            yaml.save(file);
        } catch (Exception ex) {
            plugin.getLogger().severe("Écriture de block-states.yml impossible : " + ex.getMessage());
        }
    }

    /**
     * Garantit {@code count} états à ce bloc (en réutilisant ceux déjà attribués).
     *
     * @return les états attribués, ou null s'il n'en reste plus
     */
    public List<String> assign(String id, int count) {
        List<String> states = idToStates.computeIfAbsent(id, key -> new ArrayList<>());

        for (String state : pool) {
            if (states.size() >= count) break;
            if (stateToEntry.containsKey(state)) continue;
            states.add(state);
            stateToEntry.put(state, new Entry(id, states.size() - 1));
        }

        if (states.size() < count) {
            plugin.getLogger().severe("[" + id + "] plus aucun état de bloc disponible (" + pool.size() + " au total).");
            return null;
        }
        return new ArrayList<>(states.subList(0, count));
    }

    public List<String> statesOf(String id) {
        return idToStates.getOrDefault(id, List.of());
    }

    public Entry find(String state) {
        return stateToEntry.get(state);
    }

    /** "minecraft:note_block[instrument=harp,note=1,powered=false]" devient "instrument=harp,note=1,powered=false". */
    public static String variantKey(String state) {
        int open = state.indexOf('[');
        int close = state.lastIndexOf(']');
        return open < 0 || close < open ? state : state.substring(open + 1, close);
    }
}

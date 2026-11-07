package fr.customblock;

import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Mode « mod client » (optionnel, {@code client-mod.enabled}) pour les escaliers.
 *
 * <p>Le serveur pose un vrai escalier vanilla (orientation, coins, eau et collisions gérés par Minecraft) et mémorise
 * dans le chunk quelle position est un escalier custom. Les joueurs qui ont le mod CustomBlock Client reçoivent ces
 * positions par message de plugin ; le mod affiche alors, côté client seulement, un bloc d'escalier à l'apparence du
 * resource pack. Sans le mod, le joueur voit un escalier vanilla.
 */
public final class ClientModManager implements Listener, PluginMessageListener {

    public static final String HELLO = "customblock:hello";
    public static final String SYNC = "customblock:sync";
    /** Nombre maximal d'escaliers custom différents (limite des identifiants d'états côté client). */
    public static final int MAX_SLOTS = 40;

    private static final int TYPE_SET = 1;
    private static final int TYPE_REMOVE = 2;

    private final Main plugin;
    private final CustomBlockManager manager;
    private final NamespacedKey trackKey;
    private final Map<String, Integer> slots = new LinkedHashMap<>();
    private final Set<UUID> modded = new HashSet<>();

    public ClientModManager(Main plugin, CustomBlockManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.trackKey = new NamespacedKey(plugin, "client_stairs");
    }

    public boolean active() {
        return plugin.getConfig().getBoolean("client-mod.enabled", false);
    }

    public Map<String, Integer> slots() {
        return slots;
    }

    // ------------------------------------------------------------------ numéros d'emplacement

    private File slotFile() {
        return new File(plugin.getDataFolder(), "generated/stairs-slots.yml");
    }

    /** Attribue un emplacement stable à chaque escalier custom (le fichier ne doit jamais être supprimé). */
    public void loadSlots(Collection<CustomBlockManager.BlockDefinition> definitions) {
        slots.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(slotFile());
        for (String key : yaml.getKeys(false)) {
            slots.put(key, yaml.getInt(key));
        }
        boolean changed = false;
        for (CustomBlockManager.BlockDefinition def : definitions) {
            if (!def.shape().equals("STAIRS") || slots.containsKey(def.id())) continue;
            int free = -1;
            for (int i = 0; i < MAX_SLOTS && free < 0; i++) {
                if (!slots.containsValue(i)) free = i;
            }
            if (free < 0) {
                plugin.getLogger().severe("[" + def.id() + "] plus d'emplacement d'escalier pour le mod client (max " + MAX_SLOTS + ").");
                continue;
            }
            slots.put(def.id(), free);
            changed = true;
        }
        if (changed) {
            YamlConfiguration out = new YamlConfiguration();
            slots.forEach(out::set);
            try {
                File file = slotFile();
                if (file.getParentFile() != null) file.getParentFile().mkdirs();
                out.save(file);
            } catch (IOException e) {
                plugin.getLogger().warning("Impossible d'enregistrer stairs-slots.yml : " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ suivi des positions (dans les chunks)

    private Map<String, String> read(Chunk chunk) {
        Map<String, String> map = new LinkedHashMap<>();
        String raw = chunk.getPersistentDataContainer().get(trackKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) return map;
        for (String entry : raw.split(";")) {
            int eq = entry.indexOf('=');
            if (eq > 0) map.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return map;
    }

    private void write(Chunk chunk, Map<String, String> map) {
        if (map.isEmpty()) {
            chunk.getPersistentDataContainer().remove(trackKey);
            return;
        }
        StringBuilder out = new StringBuilder();
        map.forEach((pos, id) -> out.append(out.length() == 0 ? "" : ";").append(pos).append('=').append(id));
        chunk.getPersistentDataContainer().set(trackKey, PersistentDataType.STRING, out.toString());
    }

    /** ID de l'escalier custom posé ici, ou null. */
    public String idAt(Block block) {
        if (!Tag.STAIRS.isTagged(block.getType())) return null;
        return read(block.getChunk()).get(CustomBlockManager.posKey(block));
    }

    public void track(Block block, String id) {
        Chunk chunk = block.getChunk();
        Map<String, String> map = read(chunk);
        map.put(CustomBlockManager.posKey(block), id);
        write(chunk, map);
        Integer slot = slots.get(id);
        if (slot != null) broadcastSet(chunk, List.of(new Entry(block.getX(), block.getY(), block.getZ(), slot)));
    }

    public void untrack(Block block) {
        Chunk chunk = block.getChunk();
        Map<String, String> map = read(chunk);
        if (map.remove(CustomBlockManager.posKey(block)) == null) return;
        write(chunk, map);
        broadcastRemove(chunk, List.of(new int[]{block.getX(), block.getY(), block.getZ()}));
    }

    private record Entry(int x, int y, int z, int slot) {
    }

    // ------------------------------------------------------------------ événements

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!active()) return;
        CustomBlockManager.BlockDefinition def = manager.getDefinition(event.getItemInHand());
        if (def == null || !def.shape().equals("STAIRS")) return;
        Block placed = event.getBlockPlaced();
        if (!Tag.STAIRS.isTagged(placed.getType())) return;
        track(placed, def.id());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        modded.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onChunkLoad(PlayerChunkLoadEvent event) {
        if (!active() || !modded.contains(event.getPlayer().getUniqueId())) return;
        sendChunk(event.getPlayer(), event.getChunk());
    }

    /** Le mod annonce sa présence : on lui envoie les escaliers des chunks autour de lui. */
    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (!channel.equals(HELLO) || !active()) return;
        modded.add(player.getUniqueId());
        World world = player.getWorld();
        int radius = Math.min(player.getViewDistance(), 16);
        int cx = player.getLocation().getBlockX() >> 4;
        int cz = player.getLocation().getBlockZ() >> 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (world.isChunkLoaded(cx + dx, cz + dz)) {
                    sendChunk(player, world.getChunkAt(cx + dx, cz + dz));
                }
            }
        }
    }

    // ------------------------------------------------------------------ envoi

    private void sendChunk(Player player, Chunk chunk) {
        Map<String, String> map = read(chunk);
        List<Entry> entries = new ArrayList<>();
        boolean dirty = false;
        World world = chunk.getWorld();
        for (Map.Entry<String, String> e : new HashMap<>(map).entrySet()) {
            String[] p = e.getKey().split(",");
            try {
                int x = Integer.parseInt(p[0]);
                int y = Integer.parseInt(p[1]);
                int z = Integer.parseInt(p[2]);
                if (!Tag.STAIRS.isTagged(world.getBlockAt(x, y, z).getType())) {
                    map.remove(e.getKey()); // escalier détruit sans passer par le plugin
                    dirty = true;
                    continue;
                }
                Integer slot = slots.get(e.getValue());
                if (slot != null) entries.add(new Entry(x, y, z, slot));
            } catch (NumberFormatException | ArrayIndexOutOfBoundsException ex) {
                map.remove(e.getKey());
                dirty = true;
            }
        }
        if (dirty) write(chunk, map);
        send(player, encodeChunk(chunk.getX(), chunk.getZ(), entries));
    }

    private void broadcastSet(Chunk chunk, List<Entry> entries) {
        byte[] data = encodeSet(entries);
        for (Player player : chunk.getPlayersSeeingChunk()) {
            if (modded.contains(player.getUniqueId())) send(player, data);
        }
    }

    private void broadcastRemove(Chunk chunk, List<int[]> positions) {
        byte[] data = encodeRemove(positions);
        for (Player player : chunk.getPlayersSeeingChunk()) {
            if (modded.contains(player.getUniqueId())) send(player, data);
        }
    }

    private void send(Player player, byte[] data) {
        if (data == null) return;
        player.sendPluginMessage(plugin, SYNC, data);
    }

    private byte[] encodeSet(List<Entry> entries) {
        if (entries.isEmpty()) return null;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(TYPE_SET);
            out.writeInt(entries.size());
            for (Entry e : entries) {
                out.writeInt(e.x());
                out.writeInt(e.y());
                out.writeInt(e.z());
                out.writeShort(e.slot());
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Contenu complet d'un chunk (remplace ce que le client connaît de ce chunk). */
    private byte[] encodeChunk(int cx, int cz, List<Entry> entries) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(3);
            out.writeInt(cx);
            out.writeInt(cz);
            out.writeInt(entries.size());
            for (Entry e : entries) {
                out.writeInt(e.x());
                out.writeInt(e.y());
                out.writeInt(e.z());
                out.writeShort(e.slot());
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    private byte[] encodeRemove(List<int[]> positions) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(TYPE_REMOVE);
            out.writeInt(positions.size());
            for (int[] p : positions) {
                out.writeInt(p[0]);
                out.writeInt(p[1]);
                out.writeInt(p[2]);
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** Material utilisé comme objet de base des escaliers en mode mod client. */
    public static Material baseMaterial() {
        return Material.OAK_STAIRS;
    }
}

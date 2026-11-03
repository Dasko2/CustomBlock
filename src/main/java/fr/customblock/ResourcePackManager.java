package fr.customblock;

import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Génère automatiquement le resource pack à partir des textures déposées dans
 * plugins/CustomBlock/textures/, l'héberge (HTTP intégré) et l'envoie aux joueurs.
 */
public final class ResourcePackManager {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    // Date fixe : un contenu identique donne toujours le même SHA-1 (pas de re-téléchargement inutile)
    private static final long FIXED_TIME = 978307200000L;

    private final Main plugin;
    private final File texturesDir;
    private final File packFile;
    private final String namespace;
    private final UUID packId = UUID.nameUUIDFromBytes("customblock-resource-pack".getBytes(StandardCharsets.UTF_8));

    private HttpServer server;
    private ExecutorService serverExecutor;
    private int serverPort = -1;

    private volatile byte[] packBytes;
    private volatile byte[] packSha1;
    private volatile boolean ready;
    private int textureCount;

    public ResourcePackManager(Main plugin) {
        this.plugin = plugin;
        this.texturesDir = new File(plugin.getDataFolder(), "textures");
        this.packFile = new File(plugin.getDataFolder(), "generated/resource-pack.zip");
        this.namespace = plugin.getName().toLowerCase(Locale.ROOT);
    }

    public int getTextureCount() {
        return textureCount;
    }

    public boolean isReady() {
        return ready;
    }

    /** Régénère le pack depuis les textures et redémarre l'hébergement si nécessaire. */
    public void rebuild(Collection<CustomBlockManager.BlockDefinition> definitions,
                        Collection<CustomItemManager.ItemDefinition> items) {
        ready = false;
        textureCount = 0;

        if (!texturesDir.exists() && !texturesDir.mkdirs()) {
            plugin.getLogger().warning("Impossible de créer le dossier " + texturesDir.getPath());
        }

        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("resource-pack");
        if (cfg != null && !cfg.getBoolean("enabled", true)) {
            stopServer();
            return;
        }

        int format = cfg == null ? 84 : cfg.getInt("pack-format", 84);
        String description = cfg == null ? "Textures CustomBlock" : cfg.getString("description", "Textures CustomBlock");

        byte[] bytes;
        int count = 0;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                addEntry(zip, "pack.mcmeta", mcmeta(format, description).getBytes(StandardCharsets.UTF_8));

                for (CustomBlockManager.BlockDefinition def : definitions) {
                    if (def.texture() == null) continue;

                    File file = resolveFile(def.id(), def.texture());
                    if (file == null) continue;

                    String id = def.id();
                    addEntry(zip, "assets/" + namespace + "/textures/block/" + id + ".png",
                            Files.readAllBytes(file.toPath()));
                    addEntry(zip, "assets/" + namespace + "/models/block/" + id + ".json",
                            ("{\"parent\":\"minecraft:block/cube_all\",\"textures\":{\"all\":\""
                                    + namespace + ":block/" + id + "\"}}").getBytes(StandardCharsets.UTF_8));
                    addEntry(zip, "assets/" + namespace + "/items/" + id + ".json",
                            ("{\"model\":{\"type\":\"minecraft:model\",\"model\":\""
                                    + namespace + ":block/" + id + "\"}}").getBytes(StandardCharsets.UTF_8));
                    count++;
                }

                // Outils, armes, armures et objets simples
                Set<String> armorDone = new HashSet<>();
                for (CustomItemManager.ItemDefinition item : items) {
                    String id = item.id();

                    if (item.texture() != null) {
                        File icon = resolveFile(id, item.texture());
                        if (icon != null) {
                            String parent = isHandheld(item.material()) ? "minecraft:item/handheld" : "minecraft:item/generated";
                            addEntry(zip, "assets/" + namespace + "/textures/item/" + id + ".png",
                                    Files.readAllBytes(icon.toPath()));
                            addEntry(zip, "assets/" + namespace + "/models/item/" + id + ".json",
                                    ("{\"parent\":\"" + parent + "\",\"textures\":{\"layer0\":\""
                                            + namespace + ":item/" + id + "\"}}").getBytes(StandardCharsets.UTF_8));
                            addEntry(zip, "assets/" + namespace + "/items/" + id + ".json",
                                    ("{\"model\":{\"type\":\"minecraft:model\",\"model\":\""
                                            + namespace + ":item/" + id + "\"}}").getBytes(StandardCharsets.UTF_8));
                            count++;
                        }
                    }

                    // Texture portée de l'armure : <armor-texture>_layer_1.png (casque, plastron, bottes)
                    // et <armor-texture>_layer_2.png (jambières). Générée une seule fois par set.
                    String armor = item.armorTexture();
                    if (armor != null && armorDone.add(armor)) {
                        File layer1 = resolveFile(id, armor + "_layer_1.png");
                        if (layer1 == null) continue;
                        File layer2 = resolveFile(id, armor + "_layer_2.png");

                        addEntry(zip, "assets/" + namespace + "/textures/entity/equipment/humanoid/" + armor + ".png",
                                Files.readAllBytes(layer1.toPath()));
                        String layers = "\"humanoid\":[{\"texture\":\"" + namespace + ":" + armor + "\"}]";
                        if (layer2 != null) {
                            addEntry(zip, "assets/" + namespace + "/textures/entity/equipment/humanoid_leggings/" + armor + ".png",
                                    Files.readAllBytes(layer2.toPath()));
                            layers += ",\"humanoid_leggings\":[{\"texture\":\"" + namespace + ":" + armor + "\"}]";
                        }
                        addEntry(zip, "assets/" + namespace + "/equipment/" + armor + ".json",
                                ("{\"layers\":{" + layers + "}}").getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            bytes = buffer.toByteArray();
        } catch (IOException ex) {
            plugin.getLogger().severe("Échec de la génération du resource pack : " + ex.getMessage());
            return;
        }

        textureCount = count;
        if (count == 0) {
            plugin.getLogger().info("Aucune texture trouvée dans " + texturesDir.getPath() + " : pas de resource pack généré.");
            stopServer();
            return;
        }

        try {
            File parent = packFile.getParentFile();
            if (parent != null) parent.mkdirs();
            Files.write(packFile.toPath(), bytes);
            this.packSha1 = MessageDigest.getInstance("SHA-1").digest(bytes);
        } catch (IOException | NoSuchAlgorithmException ex) {
            plugin.getLogger().severe("Échec de l'écriture du resource pack : " + ex.getMessage());
            return;
        }
        this.packBytes = bytes;

        String external = cfg == null ? "" : cfg.getString("external-url", "");
        if (external == null || external.isBlank()) {
            startServer(cfg == null ? 8163 : cfg.getInt("self-host.port", 8163));
        } else {
            stopServer();
        }

        ready = true;
        plugin.getLogger().info("Resource pack généré (" + count + " texture(s)) : " + packFile.getPath());
        plugin.getLogger().info("URL envoyée aux joueurs : " + getUrl());
        if (isLocalHost(cfg)) {
            plugin.getLogger().warning("resource-pack.self-host.host vaut 127.0.0.1 : seuls les joueurs sur la même machine pourront télécharger le pack. Mets l'IP publique ou le domaine du serveur.");
        }
    }

    /** Envoie le pack à un joueur (si un pack est prêt). */
    public void send(Player player) {
        if (!ready) return;

        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("resource-pack");
        boolean required = cfg != null && cfg.getBoolean("required", false);
        String prompt = cfg == null ? "" : cfg.getString("prompt", "");

        player.setResourcePack(
                packId,
                getUrl(),
                packSha1,
                (prompt == null || prompt.isBlank()) ? null : MM.deserialize(prompt),
                required);
    }

    public void sendToAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            send(player);
        }
    }

    public void shutdown() {
        stopServer();
    }

    public String getUrl() {
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("resource-pack");
        String external = cfg == null ? "" : cfg.getString("external-url", "");
        if (external != null && !external.isBlank()) {
            return external;
        }
        String host = cfg == null ? "127.0.0.1" : cfg.getString("self-host.host", "127.0.0.1");
        int port = cfg == null ? 8163 : cfg.getInt("self-host.port", 8163);
        return "http://" + host + ":" + port + "/pack.zip";
    }

    private boolean isLocalHost(ConfigurationSection cfg) {
        if (cfg == null) return true;
        String external = cfg.getString("external-url", "");
        if (external != null && !external.isBlank()) return false;
        String host = cfg.getString("self-host.host", "127.0.0.1");
        return host.equals("127.0.0.1") || host.equalsIgnoreCase("localhost");
    }

    private static boolean isHandheld(Material material) {
        String name = material.name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE") || name.endsWith("_PICKAXE")
                || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                || name.equals("MACE") || name.equals("STICK") || name.equals("BLAZE_ROD");
    }

    private File resolveFile(String ownerId, String name) {
        if (!name.toLowerCase(Locale.ROOT).endsWith(".png")) {
            plugin.getLogger().warning("[" + ownerId + "] la texture '" + name + "' doit être un fichier .png : ignorée.");
            return null;
        }

        File file = new File(texturesDir, name);
        try {
            String base = texturesDir.getCanonicalPath() + File.separator;
            if (!file.getCanonicalPath().startsWith(base)) {
                plugin.getLogger().warning("[" + ownerId + "] chemin de texture interdit : " + name);
                return null;
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("[" + ownerId + "] chemin de texture illisible : " + name);
            return null;
        }

        if (!file.isFile()) {
            plugin.getLogger().warning("[" + ownerId + "] texture introuvable : dépose '" + name
                    + "' dans " + texturesDir.getPath());
            return null;
        }
        return file;
    }

    private static String mcmeta(int format, String description) {
        String escaped = description.replace("\\", "\\\\").replace("\"", "\\\"");
        return "{\"pack\":{\"pack_format\":" + format
                + ",\"min_format\":" + format
                + ",\"max_format\":" + format
                + ",\"description\":\"" + escaped + "\"}}";
    }

    private static void addEntry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_TIME);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private void startServer(int port) {
        if (server != null && serverPort == port) {
            return; // déjà en ligne : le contenu est lu dynamiquement
        }
        stopServer();

        try {
            HttpServer created = HttpServer.create(new InetSocketAddress(port), 0);
            created.createContext("/pack.zip", exchange -> {
                byte[] data = packBytes;
                try (exchange) {
                    if (data == null) {
                        exchange.sendResponseHeaders(404, -1);
                        return;
                    }
                    exchange.getResponseHeaders().add("Content-Type", "application/zip");
                    exchange.sendResponseHeaders(200, data.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(data);
                    }
                }
            });
            ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "CustomBlock-PackServer");
                thread.setDaemon(true);
                return thread;
            });
            created.setExecutor(executor);
            created.start();

            this.server = created;
            this.serverExecutor = executor;
            this.serverPort = port;
            plugin.getLogger().info("Hébergement du resource pack démarré sur le port " + port + ".");
        } catch (IOException ex) {
            plugin.getLogger().severe("Impossible de démarrer l'hébergement du pack sur le port " + port + " : " + ex.getMessage());
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor = null;
        }
        serverPort = -1;
    }
}

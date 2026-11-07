package fr.customblock;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /** SHA-1 du pack courant en hexadécimal (chaîne vide s'il n'y a pas de pack). */
    public String getSha1Hex() {
        byte[] sha = packSha1;
        if (sha == null) return "";
        StringBuilder out = new StringBuilder();
        for (byte b : sha) out.append(String.format("%02x", b));
        return out.toString();
    }

    public int getTextureCount() {
        return textureCount;
    }

    public boolean isReady() {
        return ready;
    }

    /** Régénère le pack depuis les textures et redémarre l'hébergement si nécessaire. */
    public void rebuild(Collection<CustomBlockManager.BlockDefinition> definitions,
                        Collection<CustomItemManager.ItemDefinition> items,
                        BlockStateRegistry registry) {
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

        int format = resolvePackFormat(cfg);
        String description = cfg == null ? "Textures CustomBlock" : cfg.getString("description", "Textures CustomBlock");

        byte[] bytes;
        int count = 0;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
                addEntry(zip, "pack.mcmeta", mcmeta(format, description).getBytes(StandardCharsets.UTF_8));

                // ---- Blocs : vrais états de note_block, modèle cube avec une texture par face ----
                Map<String, String> stateModels = new LinkedHashMap<>();
                Map<String, String> shapeStates = new LinkedHashMap<>();
                String slabParticle = null;
                boolean petrified = false;
                for (CustomBlockManager.BlockDefinition def : definitions) {
                    if (def.textures().isEmpty()) continue;

                    String id = def.id();
                    List<String> states = registry.statesOf(id);
                    boolean slab = "SLAB".equals(def.shape());
                    boolean stairs = "STAIRS".equals(def.shape());
                    boolean cross = "CROSS".equals(def.shape());
                    if (states.isEmpty() && !slab && !stairs && !cross) continue;

                    Map<String, String> resourceByFile = new LinkedHashMap<>();
                    Map<String, String> resourceByFace = new LinkedHashMap<>();
                    boolean valid = true;

                    for (Map.Entry<String, String> face : def.textures().entrySet()) {
                        String fileName = face.getValue();
                        String resource = resourceByFile.get(fileName);
                        if (resource == null) {
                            File file = resolveFile(id, fileName);
                            if (file == null) {
                                valid = false;
                                break;
                            }
                            resource = id + "_" + resourceByFile.size();
                            resourceByFile.put(fileName, resource);
                            addEntry(zip, "assets/" + namespace + "/textures/block/" + resource + ".png",
                                    Files.readAllBytes(file.toPath()));
                        }
                        resourceByFace.put(face.getKey(), resource);
                    }
                    if (!valid) continue;

                    StringBuilder textures = new StringBuilder();
                    textures.append("\"particle\":\"").append(namespace).append(":block/").append(resourceByFace.get("north")).append("\"");
                    for (String face : new String[]{"down", "up", "north", "south", "east", "west"}) {
                        textures.append(",\"").append(face).append("\":\"")
                                .append(namespace).append(":block/").append(resourceByFace.get(face)).append("\"");
                    }
                    if (!cross && !stairs) {
                        addEntry(zip, "assets/" + namespace + "/models/block/" + id + ".json",
                                ("{\"parent\":\"minecraft:block/cube\",\"textures\":{" + textures + "}}")
                                        .getBytes(StandardCharsets.UTF_8));
                    }
                    String itemTarget = namespace + ":block/" + id;

                    if (stairs) {
                        // Escalier droit : modèle vanilla stairs ; le modèle affiché est tourné par le plugin
                        String stairTextures = "{\"particle\":\"" + namespace + ":block/" + resourceByFace.get("north")
                                + "\",\"bottom\":\"" + namespace + ":block/" + resourceByFace.get("down")
                                + "\",\"top\":\"" + namespace + ":block/" + resourceByFace.get("up")
                                + "\",\"side\":\"" + namespace + ":block/" + resourceByFace.get("north") + "\"}";
                        addModel(zip, id + "_stairs", "minecraft:block/stairs", stairTextures);
                        itemTarget = namespace + ":block/" + id + "_stairs";
                        if (slabParticle == null) slabParticle = namespace + ":block/" + resourceByFace.get("north");
                        petrified = true;
                    }
                    if (slab) {
                        // Dalle : modèles vanilla slab / slab_top ; le bloc de collision est rendu invisible plus bas
                        String shapeTextures = "{\"particle\":\"" + namespace + ":block/" + resourceByFace.get("north")
                                + "\",\"bottom\":\"" + namespace + ":block/" + resourceByFace.get("down")
                                + "\",\"top\":\"" + namespace + ":block/" + resourceByFace.get("up")
                                + "\",\"side\":\"" + namespace + ":block/" + resourceByFace.get("north") + "\"}";
                        addModel(zip, id + "_slab", "minecraft:block/slab", shapeTextures);
                        addModel(zip, id + "_slab_top", "minecraft:block/slab_top", shapeTextures);
                        // Objets affichés sur la dalle posée : dalle du haut et dalle double
                        addEntry(zip, "assets/" + namespace + "/items/" + id + "_top.json",
                                ("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + namespace + ":block/" + id + "_slab_top\"}}")
                                        .getBytes(StandardCharsets.UTF_8));
                        addEntry(zip, "assets/" + namespace + "/items/" + id + "_double.json",
                                ("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + namespace + ":block/" + id + "\"}}")
                                        .getBytes(StandardCharsets.UTF_8));
                        itemTarget = namespace + ":block/" + id + "_slab";
                        if (slabParticle == null) slabParticle = namespace + ":block/" + resourceByFace.get("north");
                        petrified = true;
                    }
                    String itemJson = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + itemTarget + "\"}}";
                    if (cross) {
                        // Plante : icône plate dans l'inventaire / en main, modèle en croix une fois posée (affichage)
                        String res = resourceByFace.get("north");
                        addModel(zip, id + "_cross", "minecraft:block/cross",
                                "{\"cross\":\"" + namespace + ":block/" + res + "\"}");
                        addEntry(zip, "assets/" + namespace + "/models/item/" + id + ".json",
                                ("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\""
                                        + namespace + ":block/" + res + "\"}}").getBytes(StandardCharsets.UTF_8));
                        itemJson = "{\"model\":{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\","
                                + "\"cases\":[{\"when\":[\"gui\",\"ground\",\"fixed\",\"thirdperson_righthand\",\"thirdperson_lefthand\","
                                + "\"firstperson_righthand\",\"firstperson_lefthand\",\"head\"],"
                                + "\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + namespace + ":item/" + id + "\"}}],"
                                + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"" + namespace + ":block/" + id + "_cross\"}}}";
                    }
                    addEntry(zip, "assets/" + namespace + "/items/" + id + ".json", itemJson.getBytes(StandardCharsets.UTF_8));

                    if (!slab && !stairs && !cross) {
                        // Variante 0 : bloc normal (ou tronc debout) ; 1 : tronc sur l'axe X ; 2 : tronc sur l'axe Z.
                        // Bloc orienté : variantes 0 à 3 = rotation de 0, 90, 180 et 270 degrés.
                        // Grand bloc : les cases sont invisibles, le modèle agrandi est affiché par une entité
                        if (def.size() != null && slabParticle == null) {
                            slabParticle = namespace + ":block/" + resourceByFace.get("north");
                        }
                        String model = def.size() != null ? namespace + ":block/empty" : namespace + ":block/" + id;
                        stateModels.put(BlockStateRegistry.variantKey(states.get(0)), "{\"model\":\"" + model + "\"}");
                        if (def.axis() && states.size() >= 3) {
                            stateModels.put(BlockStateRegistry.variantKey(states.get(1)),
                                    "{\"model\":\"" + model + "\",\"x\":90,\"y\":90}");
                            stateModels.put(BlockStateRegistry.variantKey(states.get(2)),
                                    "{\"model\":\"" + model + "\",\"x\":90}");
                        }
                        if (def.facing() && states.size() >= 4) {
                            for (int turn = 1; turn <= 3; turn++) {
                                stateModels.put(BlockStateRegistry.variantKey(states.get(turn)),
                                        "{\"model\":\"" + model + "\",\"y\":" + (turn * 90) + "}");
                            }
                        }
                    }
                    count++;
                }
                if (slabParticle != null) {
                    // La dalle de chêne pétrifié (introuvable en survie) devient invisible : seule sa collision reste,
                    // l'apparence vient du modèle affiché par le plugin.
                    addEntry(zip, "assets/" + namespace + "/models/block/empty.json",
                            ("{\"textures\":{\"particle\":\"" + slabParticle + "\"},\"elements\":[]}").getBytes(StandardCharsets.UTF_8));
                    String empty = "{\"model\":\"" + namespace + ":block/empty\"}";
                    if (petrified) shapeStates.put("petrified_oak_slab", "{\"variants\":{\"type=bottom\":" + empty
                            + ",\"type=top\":" + empty + ",\"type=double\":" + empty + "}}");
                }
                for (Map.Entry<String, String> entry : shapeStates.entrySet()) {
                    addEntry(zip, "assets/minecraft/blockstates/" + entry.getKey() + ".json",
                            entry.getValue().getBytes(StandardCharsets.UTF_8));
                }
                if (!stateModels.isEmpty()) {
                    addEntry(zip, "assets/minecraft/blockstates/note_block.json",
                            noteBlockStates(stateModels).getBytes(StandardCharsets.UTF_8));
                }

                // ---- Objets : icône, texture en main et armure portée ----
                Set<String> armorDone = new HashSet<>();
                for (CustomItemManager.ItemDefinition item : items) {
                    String id = item.id();
                    String parent = isHandheld(item.material()) ? "minecraft:item/handheld" : "minecraft:item/generated";

                    if (item.texture() != null) {
                        File icon = resolveFile(id, item.texture());
                        if (icon != null) {
                            addEntry(zip, "assets/" + namespace + "/textures/item/" + id + ".png",
                                    Files.readAllBytes(icon.toPath()));
                            addEntry(zip, "assets/" + namespace + "/models/item/" + id + ".json",
                                    itemModel(parent, id).getBytes(StandardCharsets.UTF_8));

                            String itemDefinition = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\""
                                    + namespace + ":item/" + id + "\"}}";

                            File held = (item.heldTexture() == null || item.heldTexture().equals(item.texture()))
                                    ? null : resolveFile(id, item.heldTexture());
                            if (held != null) {
                                String heldId = id + "_held";
                                addEntry(zip, "assets/" + namespace + "/textures/item/" + heldId + ".png",
                                        Files.readAllBytes(held.toPath()));
                                addEntry(zip, "assets/" + namespace + "/models/item/" + heldId + ".json",
                                        itemModel(parent, heldId).getBytes(StandardCharsets.UTF_8));
                                // Icône dans l'inventaire / au sol / en cadre ; texture "en main" partout ailleurs
                                itemDefinition = "{\"model\":{\"type\":\"minecraft:select\","
                                        + "\"property\":\"minecraft:display_context\","
                                        + "\"cases\":[{\"when\":[\"gui\",\"ground\",\"fixed\"],"
                                        + "\"model\":{\"type\":\"minecraft:model\",\"model\":\"" + namespace + ":item/" + id + "\"}}],"
                                        + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"" + namespace + ":item/" + heldId + "\"}}}";
                            }
                            addEntry(zip, "assets/" + namespace + "/items/" + id + ".json",
                                    itemDefinition.getBytes(StandardCharsets.UTF_8));
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

                // ---- Sons : chaque plugins/CustomBlock/sounds/<nom>.ogg devient l'événement customblock:<nom> ----
                File soundsDir = new File(plugin.getDataFolder(), "sounds");
                File[] oggs = soundsDir.isDirectory() ? soundsDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".ogg")) : null;
                if (oggs != null && oggs.length > 0) {
                    StringBuilder json = new StringBuilder("{");
                    boolean first = true;
                    for (File ogg : oggs) {
                        String name = ogg.getName().substring(0, ogg.getName().length() - 4).toLowerCase(Locale.ROOT);
                        if (!name.matches("[a-z0-9_.-]+")) {
                            plugin.getLogger().warning("Son ignoré (nom invalide, utilise a-z 0-9 _ . -) : " + ogg.getName());
                            continue;
                        }
                        addEntry(zip, "assets/" + namespace + "/sounds/" + name + ".ogg", Files.readAllBytes(ogg.toPath()));
                        if (!first) json.append(',');
                        first = false;
                        json.append('"').append(name).append("\":{\"sounds\":[\"").append(namespace).append(':').append(name).append("\"]}");
                    }
                    json.append('}');
                    if (!first) {
                        addEntry(zip, "assets/" + namespace + "/sounds.json", json.toString().getBytes(StandardCharsets.UTF_8));
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
        plugin.getLogger().info("Resource pack généré (" + count + " modèle(s), format " + format + ") : " + packFile.getPath());
        plugin.getLogger().info("URL envoyée aux joueurs : " + getUrl());
        if (isLocalHost(cfg)) {
            plugin.getLogger().warning("resource-pack.self-host.host vaut 127.0.0.1 : seuls les joueurs sur la même machine pourront télécharger le pack. Mets l'IP publique ou le domaine du serveur.");
        }
    }

    /**
     * Fichier blockstates du bloc-note : un modèle pour chacun des 1150 états.
     * Les états de nos blocs pointent vers leur modèle, tous les autres gardent le modèle vanilla.
     */
    private void addModel(ZipOutputStream zip, String name, String parent, String texturesJson) throws IOException {
        addEntry(zip, "assets/" + namespace + "/models/block/" + name + ".json",
                ("{\"parent\":\"" + parent + "\",\"textures\":" + texturesJson + "}").getBytes(StandardCharsets.UTF_8));
    }

    private static String noteBlockStates(Map<String, String> customModels) {
        StringBuilder json = new StringBuilder("{\"variants\":{");
        boolean first = true;
        for (String instrument : BlockStateRegistry.ALL_INSTRUMENTS) {
            for (int note = 0; note <= 24; note++) {
                for (boolean powered : new boolean[]{false, true}) {
                    String key = "instrument=" + instrument + ",note=" + note + ",powered=" + powered;
                    String value = customModels.getOrDefault(key, "{\"model\":\"minecraft:block/note_block\"}");
                    if (!first) json.append(',');
                    first = false;
                    json.append('"').append(key).append("\":").append(value);
                }
            }
        }
        return json.append("}}").toString();
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

    /**
     * Numéro de format du pack : "auto" le lit dans le serveur lui-même (version.json),
     * sinon le nombre indiqué dans la config est utilisé.
     */
    private int resolvePackFormat(ConfigurationSection cfg) {
        int fallback = cfg == null ? 84 : cfg.getInt("pack-format-fallback", 84);
        String raw = cfg == null ? "auto" : cfg.getString("pack-format", "auto");

        if (raw != null && !raw.equalsIgnoreCase("auto")) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                plugin.getLogger().warning("resource-pack.pack-format invalide ('" + raw + "') : détection automatique.");
            }
        }

        int detected = detectPackFormat();
        if (detected > 0) {
            return detected;
        }
        plugin.getLogger().warning("Format du pack non détectable automatiquement : utilisation de pack-format-fallback (" + fallback + ").");
        return fallback;
    }

    private int detectPackFormat() {
        try (InputStream in = Bukkit.class.getResourceAsStream("/version.json")) {
            if (in == null) return -1;

            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject packVersion = root.getAsJsonObject("pack_version");
            if (packVersion == null) return -1;

            // Versions récentes : resource_major / resource_minor ; anciennes : resource
            if (packVersion.has("resource_major")) return packVersion.get("resource_major").getAsInt();
            if (packVersion.has("resource")) return packVersion.get("resource").getAsInt();
        } catch (Exception ex) {
            plugin.getLogger().fine("Lecture de version.json impossible : " + ex.getMessage());
        }
        return -1;
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

    private String itemModel(String parent, String textureId) {
        return "{\"parent\":\"" + parent + "\",\"textures\":{\"layer0\":\"" + namespace + ":item/" + textureId + "\"}}";
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

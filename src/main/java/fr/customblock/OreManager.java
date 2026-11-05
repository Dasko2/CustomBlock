package fr.customblock;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Génération de minerais custom dans le monde : section "generation" d'un bloc de config.yml.
 * Les filons sont créés dans les nouveaux chunks, ou à la demande avec /cb ore apply.
 */
public final class OreManager implements Listener {

    public record OreDef(String id, Set<String> worlds, Set<Material> replace, int minY, int maxY,
                         double veinsPerChunk, int veinSize, Set<String> biomes) {
    }

    /** Accès aux blocs, pendant la génération d'un chunk comme sur un monde déjà chargé. */
    private interface Access {
        boolean contains(int x, int y, int z);

        Material type(int x, int y, int z);

        void set(int x, int y, int z, BlockData data);

        String biome(int x, int y, int z);
    }

    private final class Populator extends BlockPopulator {
        @Override
        public void populate(@NotNull WorldInfo info, @NotNull Random random, int chunkX, int chunkZ,
                             @NotNull LimitedRegion region) {
            Access access = new Access() {
                @Override
                public boolean contains(int x, int y, int z) {
                    return region.isInRegion(x, y, z);
                }

                @Override
                public Material type(int x, int y, int z) {
                    return region.getType(x, y, z);
                }

                @Override
                public void set(int x, int y, int z, BlockData data) {
                    region.setBlockData(x, y, z, data);
                }

                @Override
                public String biome(int x, int y, int z) {
                    return region.getBiome(x, y, z).getKey().getKey();
                }
            };
            generate(access, info.getName(), info.getEnvironment(), info.getMinHeight(), info.getMaxHeight(),
                    random, chunkX, chunkZ);
        }
    }

    private final Main plugin;
    private final CustomBlockManager manager;
    private final Populator populator = new Populator();
    private volatile List<OreDef> ores = List.of();

    public OreManager(Main plugin, CustomBlockManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ------------------------------------------------------------------ cycle de vie

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (World world : Bukkit.getWorlds()) {
            attach(world);
        }
    }

    public void unregister() {
        for (World world : Bukkit.getWorlds()) {
            world.getPopulators().remove(populator);
        }
    }

    @EventHandler
    public void onWorldInit(WorldInitEvent event) {
        attach(event.getWorld());
    }

    private void attach(World world) {
        if (!world.getPopulators().contains(populator)) {
            world.getPopulators().add(populator);
        }
    }

    public int getOreCount() {
        return ores.size();
    }

    // ------------------------------------------------------------------ configuration

    public void load() {
        List<OreDef> result = new ArrayList<>();

        ConfigurationSection blocks = plugin.getConfig().getConfigurationSection("blocks");
        if (blocks != null) {
            for (String key : blocks.getKeys(false)) {
                ConfigurationSection s = blocks.getConfigurationSection(key + ".generation");
                if (s == null) continue;

                String id = key.toLowerCase(Locale.ROOT);
                if (manager.getDefinition(id) == null) continue;
                if (!manager.getDefinition(id).shape().equals("CUBE")) {
                    plugin.getLogger().warning("[" + id + "] generation ignorée : seuls les blocs cubes se génèrent en filons.");
                    continue;
                }

                Set<String> worlds = new HashSet<>(s.getStringList("worlds"));

                Set<Material> replace = new HashSet<>();
                List<String> names = s.getStringList("replace");
                if (names.isEmpty()) names = List.of("STONE", "DEEPSLATE");
                for (String name : names) {
                    Material material = Material.matchMaterial(name.trim());
                    if (material == null || !material.isBlock()) {
                        plugin.getLogger().warning("[" + id + "] generation.replace : matériau inconnu '" + name + "'.");
                        continue;
                    }
                    replace.add(material);
                }
                if (replace.isEmpty()) continue;

                Set<String> biomes = new HashSet<>();
                for (String name : s.getStringList("biomes")) {
                    String normalized = name.trim().toLowerCase(Locale.ROOT);
                    biomes.add(normalized.startsWith("minecraft:") ? normalized.substring("minecraft:".length()) : normalized);
                }

                int minY = s.getInt("min-y", -64);
                int maxY = Math.max(minY, s.getInt("max-y", 16));
                double veins = Math.max(0.0, Math.min(64.0, s.getDouble("veins-per-chunk", 3.0)));
                int size = Math.max(1, Math.min(64, s.getInt("vein-size", 5)));

                result.add(new OreDef(id, worlds, replace, minY, maxY, veins, size, biomes));
            }
        }
        this.ores = List.copyOf(result);
    }

    // ------------------------------------------------------------------ génération

    private void generate(Access access, String worldName, World.Environment environment,
                          int worldMinY, int worldMaxY, Random random, int chunkX, int chunkZ) {
        for (OreDef def : ores) {
            boolean worldOk = def.worlds().isEmpty()
                    ? environment == World.Environment.NORMAL : def.worlds().contains(worldName);
            if (!worldOk) continue;

            BlockData data = manager.stateData(def.id(), 0);
            if (data == null) continue;

            int veins = (int) Math.floor(def.veinsPerChunk());
            if (random.nextDouble() < def.veinsPerChunk() - veins) veins++;

            int minY = Math.max(def.minY(), worldMinY);
            int maxY = Math.min(def.maxY(), worldMaxY - 1);
            if (maxY < minY) continue;

            for (int v = 0; v < veins; v++) {
                int x = (chunkX << 4) + random.nextInt(16);
                int z = (chunkZ << 4) + random.nextInt(16);
                int y = minY + random.nextInt(maxY - minY + 1);

                if (!def.biomes().isEmpty()
                        && (!access.contains(x, y, z) || !def.biomes().contains(access.biome(x, y, z)))) {
                    continue;
                }

                // Filon : marche aléatoire qui remplace la roche voisine
                int cx = x;
                int cy = y;
                int cz = z;
                for (int i = 0; i < def.veinSize(); i++) {
                    if (cy >= worldMinY && cy < worldMaxY && access.contains(cx, cy, cz)
                            && def.replace().contains(access.type(cx, cy, cz))) {
                        access.set(cx, cy, cz, data);
                    }
                    cx += random.nextInt(3) - 1;
                    cy += random.nextInt(3) - 1;
                    cz += random.nextInt(3) - 1;
                }
            }
        }
    }

    /**
     * Génère les filons dans les chunks déjà créés autour d'un point (les nouveaux chunks sont traités tout seuls).
     * Peut s'ajouter à des minerais déjà présents si la commande est lancée plusieurs fois.
     *
     * @return le nombre de chunks traités
     */
    public int apply(World world, int centerChunkX, int centerChunkZ, int radius) {
        Access access = new Access() {
            @Override
            public boolean contains(int x, int y, int z) {
                return y >= world.getMinHeight() && y < world.getMaxHeight() && world.isChunkLoaded(x >> 4, z >> 4);
            }

            @Override
            public Material type(int x, int y, int z) {
                return world.getBlockAt(x, y, z).getType();
            }

            @Override
            public void set(int x, int y, int z, BlockData data) {
                world.getBlockAt(x, y, z).setBlockData(data, false);
            }

            @Override
            public String biome(int x, int y, int z) {
                return world.getBiome(x, y, z).getKey().getKey();
            }
        };

        int processed = 0;
        for (int cx = centerChunkX - radius; cx <= centerChunkX + radius; cx++) {
            for (int cz = centerChunkZ - radius; cz <= centerChunkZ + radius; cz++) {
                world.getChunkAt(cx, cz);
            }
        }
        for (int cx = centerChunkX - radius; cx <= centerChunkX + radius; cx++) {
            for (int cz = centerChunkZ - radius; cz <= centerChunkZ + radius; cz++) {
                Random random = new Random(world.getSeed() ^ (cx * 341873128712L) ^ (cz * 132897987541L) ^ 0x0E5L);
                generate(access, world.getName(), world.getEnvironment(), world.getMinHeight(), world.getMaxHeight(),
                        random, cx, cz);
                processed++;
            }
        }
        return processed;
    }
}

package fr.customblock;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * "Biomes" de blocs custom : des zones où l'herbe, la terre, les arbres du monde sont remplacés par tes blocs.
 * Les zones sont générées dans les nouveaux chunks, ou à la demande avec /cb biome apply.
 *
 * Ce ne sont pas de vrais biomes Minecraft : ciel, brouillard, mobs et teinte de l'herbe restent ceux du biome vanilla.
 */
public final class BiomeManager implements Listener {

    public record TreeDef(String log, String leaves, int minHeight, int maxHeight, int leafRadius, double density) {
    }

    /** Une plante posée sur le sol de la zone : un bloc custom (ID) ou un matériau vanilla (FERN, POPPY...). */
    public record PlantDef(String block, Material material, double density) {
    }

    public record BiomeDef(
            String id,
            Set<String> worlds,
            Set<String> vanillaBiomes,
            double scale,
            double coverage,
            boolean clearVegetation,
            String top,
            String under,
            int underDepth,
            List<TreeDef> trees,
            List<PlantDef> plants
    ) {
    }

    /** Accès aux blocs, pendant la génération d'un chunk comme sur un monde déjà chargé. */
    private interface Region {
        String worldName();

        long seed();

        int minY();

        int maxY();

        /** Altitude de départ pour chercher le sol d'une colonne. */
        int top(int x, int z);

        boolean contains(int x, int y, int z);

        Material type(int x, int y, int z);

        void set(int x, int y, int z, BlockData data);

        String biome(int x, int y, int z);
    }

    private static final class GenerationRegion implements Region {
        private final WorldInfo info;
        private final LimitedRegion region;

        private GenerationRegion(WorldInfo info, LimitedRegion region) {
            this.info = info;
            this.region = region;
        }

        @Override
        public String worldName() {
            return info.getName();
        }

        @Override
        public long seed() {
            return info.getSeed();
        }

        @Override
        public int minY() {
            return info.getMinHeight();
        }

        @Override
        public int maxY() {
            return info.getMaxHeight();
        }

        @Override
        public int top(int x, int z) {
            return info.getMaxHeight() - 1;
        }

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
    }

    private static final class WorldRegion implements Region {
        private final World world;

        private WorldRegion(World world) {
            this.world = world;
        }

        @Override
        public String worldName() {
            return world.getName();
        }

        @Override
        public long seed() {
            return world.getSeed();
        }

        @Override
        public int minY() {
            return world.getMinHeight();
        }

        @Override
        public int maxY() {
            return world.getMaxHeight();
        }

        @Override
        public int top(int x, int z) {
            return world.getHighestBlockYAt(x, z);
        }

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
    }

    private final class Populator extends BlockPopulator {
        @Override
        public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
                             @NotNull LimitedRegion limitedRegion) {
            generate(new GenerationRegion(worldInfo, limitedRegion), random, chunkX, chunkZ);
        }
    }

    private final Main plugin;
    private final CustomBlockManager manager;
    private final Populator populator = new Populator();
    private volatile List<BiomeDef> biomes = List.of();

    public BiomeManager(Main plugin, CustomBlockManager manager) {
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

    public int getBiomeCount() {
        return biomes.size();
    }

    // ------------------------------------------------------------------ configuration

    public void load() {
        List<BiomeDef> result = new ArrayList<>();

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("biomes");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                ConfigurationSection s = section.getConfigurationSection(key);
                if (s == null) continue;

                String id = key.toLowerCase(Locale.ROOT);

                Set<String> worlds = new HashSet<>(s.getStringList("worlds"));
                if (worlds.isEmpty()) worlds.add("world");

                Set<String> vanilla = new HashSet<>();
                for (String name : s.getStringList("based-on")) {
                    String normalized = name.trim().toLowerCase(Locale.ROOT);
                    vanilla.add(normalized.startsWith("minecraft:") ? normalized.substring("minecraft:".length()) : normalized);
                }

                double scale = Math.max(8.0, s.getDouble("scale", 96.0));
                double coverage = Math.max(0.0, Math.min(1.0, s.getDouble("coverage", 0.4)));
                boolean clear = s.getBoolean("clear-vegetation", true);

                ConfigurationSection surface = s.getConfigurationSection("surface");
                String top = checkBlock(id, surface == null ? null : surface.getString("top"));
                String under = checkBlock(id, surface == null ? null : surface.getString("under"));
                int depth = surface == null ? 3 : Math.max(1, Math.min(6, surface.getInt("under-depth", 3)));

                List<TreeDef> trees = new ArrayList<>();
                for (Map<?, ?> entry : s.getMapList("trees")) {
                    String log = checkBlock(id, entry.get("log") == null ? null : String.valueOf(entry.get("log")));
                    String leaves = checkBlock(id, entry.get("leaves") == null ? null : String.valueOf(entry.get("leaves")));
                    if (log == null || leaves == null) {
                        plugin.getLogger().warning("[biome " + id + "] arbre ignoré : 'log' et 'leaves' doivent être des blocs définis dans 'blocks'.");
                        continue;
                    }

                    int[] height = parseRange(entry.get("height") == null ? "5-7" : String.valueOf(entry.get("height")));
                    int radius = parseInt(entry.get("leaf-radius"), 3);
                    double density = parseDouble(entry.get("density"), 0.02);

                    trees.add(new TreeDef(
                            log, leaves,
                            Math.max(2, height[0]), Math.max(2, height[1]),
                            Math.max(1, Math.min(5, radius)),
                            Math.max(0.0, Math.min(1.0, density))));
                }

                List<PlantDef> plants = new ArrayList<>();
                for (Map<?, ?> entry : s.getMapList("plants")) {
                    String block = entry.get("block") == null ? null : String.valueOf(entry.get("block")).trim();
                    if (block == null || block.isEmpty()) {
                        plugin.getLogger().warning("[biome " + id + "] plante ignorée : 'block' manquant.");
                        continue;
                    }
                    double density = Math.max(0.0, Math.min(1.0, parseDouble(entry.get("density"), 0.05)));
                    if (manager.getDefinition(block.toLowerCase(Locale.ROOT)) != null) {
                        plants.add(new PlantDef(block.toLowerCase(Locale.ROOT), null, density));
                        continue;
                    }
                    Material material = Material.matchMaterial(block);
                    if (material == null || !material.isBlock() || material.isAir()) {
                        plugin.getLogger().warning("[biome " + id + "] plante ignorée : '" + block
                                + "' n'est ni un bloc de 'blocks' ni un matériau vanilla.");
                        continue;
                    }
                    plants.add(new PlantDef(null, material, density));
                }

                if (top == null && under == null && trees.isEmpty() && plants.isEmpty()) {
                    plugin.getLogger().warning("[biome " + id + "] ignoré : ni 'surface', 'trees' ni 'plants' valides.");
                    continue;
                }

                result.add(new BiomeDef(id, worlds, vanilla, scale, coverage, clear, top, under, depth,
                        List.copyOf(trees), List.copyOf(plants)));
            }
        }

        this.biomes = List.copyOf(result);
    }

    private String checkBlock(String biomeId, String blockId) {
        if (blockId == null || blockId.isBlank()) return null;
        String normalized = blockId.trim().toLowerCase(Locale.ROOT);
        if (manager.getDefinition(normalized) == null) {
            plugin.getLogger().warning("[biome " + biomeId + "] le bloc '" + blockId + "' n'existe pas dans 'blocks'.");
            return null;
        }
        return normalized;
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
            return new int[]{5, 7};
        }
    }

    private static int parseInt(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parseDouble(Object value, double fallback) {
        if (value instanceof Number number) return number.doubleValue();
        try {
            return value == null ? fallback : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------ génération

    private void generate(Region region, Random random, int chunkX, int chunkZ) {
        for (BiomeDef def : biomes) {
            if (!def.worlds().contains(region.worldName())) continue;
            List<int[]> spots = surface(def, region, chunkX, chunkZ);
            plantTrees(def, region, random, spots);
            plantPlants(def, region, random, spots);
        }
    }

    /**
     * Applique les biomes aux chunks déjà générés autour d'un point (les nouveaux chunks sont traités tout seuls).
     *
     * @return le nombre de chunks traités
     */
    public int apply(World world, int centerChunkX, int centerChunkZ, int radius) {
        WorldRegion region = new WorldRegion(world);
        int processed = 0;

        for (BiomeDef def : biomes) {
            if (!def.worlds().contains(world.getName())) continue;

            // Phase 1 : la surface de tous les chunks, phase 2 : les arbres (sinon leurs feuilles masquent le sol)
            Map<Long, List<int[]>> spotsByChunk = new HashMap<>();
            List<int[]> chunks = new ArrayList<>();
            for (int cx = centerChunkX - radius; cx <= centerChunkX + radius; cx++) {
                for (int cz = centerChunkZ - radius; cz <= centerChunkZ + radius; cz++) {
                    world.getChunkAt(cx, cz);
                    chunks.add(new int[]{cx, cz});
                }
            }
            for (int[] chunk : chunks) {
                spotsByChunk.put(chunkKey(chunk[0], chunk[1]), surface(def, region, chunk[0], chunk[1]));
            }
            for (int[] chunk : chunks) {
                Random random = new Random(world.getSeed() ^ def.id().hashCode()
                        ^ (chunk[0] * 341873128712L) ^ (chunk[1] * 132897987541L));
                List<int[]> spots = spotsByChunk.get(chunkKey(chunk[0], chunk[1]));
                plantTrees(def, region, random, spots);
                plantPlants(def, region, random, spots);
            }
            processed = chunks.size();
        }
        return processed;
    }

    public List<String> biomeIds() {
        List<String> ids = new ArrayList<>();
        for (BiomeDef def : biomes) ids.add(def.id());
        return ids;
    }

    /** Biome défini dans la configuration (id insensible à la casse), ou null. */
    public BiomeDef find(String id) {
        for (BiomeDef def : biomes) {
            if (def.id().equalsIgnoreCase(id)) return def;
        }
        return null;
    }

    /**
     * Cherche la zone la plus proche d'un biome, en parcourant des anneaux de plus en plus larges autour du point
     * (sans générer de chunk : le bruit de zone et le biome vanilla d'origine suffisent).
     *
     * @return {x, z} d'un point de la zone, ou null si rien n'est trouvé dans le rayon
     */
    public int[] locate(World world, BiomeDef def, int centerX, int centerZ, int maxRadius) {
        if (!def.worlds().contains(world.getName())) return null;
        WorldRegion region = new WorldRegion(world);
        double threshold = 1.0 - def.coverage();
        int step = 24;
        for (int ring = 0; ring * step <= maxRadius; ring++) {
            int r = ring * step;
            int[] best = null;
            double bestDistance = Double.MAX_VALUE;
            for (int i = -ring; i <= ring; i++) {
                int[][] points = ring == 0
                        ? new int[][]{{0, 0}}
                        : new int[][]{{i * step, -r}, {i * step, r}, {-r, i * step}, {r, i * step}};
                for (int[] offset : points) {
                    int x = centerX + offset[0];
                    int z = centerZ + offset[1];
                    if (noise(world.getSeed(), def.id(), x, z, def.scale()) < threshold) continue;
                    if (!def.vanillaBiomes().isEmpty() && !def.vanillaBiomes().contains(region.biome(x, 64, z))) continue;
                    double distance = (double) offset[0] * offset[0] + (double) offset[1] * offset[1];
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new int[]{x, z};
                    }
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /** Remplace l'herbe et la terre de la zone, et retourne les emplacements possibles pour des arbres. */
    private List<int[]> surface(BiomeDef def, Region region, int chunkX, int chunkZ) {
        List<int[]> spots = new ArrayList<>();

        BlockData top = def.top() == null ? null : manager.stateData(def.top(), 0);
        BlockData under = def.under() == null ? null : manager.stateData(def.under(), 0);
        BlockData air = Material.AIR.createBlockData();

        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        double threshold = 1.0 - def.coverage();

        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                int x = baseX + dx;
                int z = baseZ + dz;

                if (noise(region.seed(), def.id(), x, z, def.scale()) < threshold) continue;

                int y = findGround(def, region, x, z, air);
                if (y == Integer.MIN_VALUE) continue;
                if (region.type(x, y, z) != Material.GRASS_BLOCK) continue;

                if (top != null) region.set(x, y, z, top);

                if (under != null) {
                    for (int d = 1; d <= def.underDepth() && y - d >= region.minY(); d++) {
                        Material below = region.type(x, y - d, z);
                        if (below == Material.DIRT || below == Material.COARSE_DIRT || below == Material.PODZOL
                                || below == Material.ROOTED_DIRT || below == Material.GRASS_BLOCK) {
                            region.set(x, y - d, z, under);
                        } else {
                            break;
                        }
                    }
                }

                if (!def.trees().isEmpty() || !def.plants().isEmpty()) spots.add(new int[]{x, y, z});
            }
        }
        return spots;
    }

    /**
     * Cherche le sol d'une colonne en partant du haut. Les arbres et plantes vanilla rencontrés sont retirés
     * si clear-vegetation est actif. Retourne Integer.MIN_VALUE si la colonne ne convient pas
     * (eau, biome vanilla non concerné, rien de solide).
     */
    private int findGround(BiomeDef def, Region region, int x, int z, BlockData air) {
        for (int y = region.top(x, z); y >= region.minY(); y--) {
            Material type = region.type(x, y, z);
            if (type.isAir()) continue;

            if (!def.vanillaBiomes().isEmpty() && !def.vanillaBiomes().contains(region.biome(x, y, z))) {
                return Integer.MIN_VALUE;
            }
            if (type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN) {
                return Integer.MIN_VALUE;
            }

            boolean vegetation = Tag.LEAVES.isTagged(type) || Tag.LOGS.isTagged(type) || !type.isSolid();
            if (vegetation) {
                if (def.clearVegetation()) region.set(x, y, z, air);
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private void plantTrees(BiomeDef def, Region region, Random random, List<int[]> spots) {
        if (spots == null || def.trees().isEmpty()) return;

        List<int[]> planted = new ArrayList<>();
        for (int[] spot : spots) {
            for (TreeDef tree : def.trees()) {
                if (random.nextDouble() >= tree.density()) continue;

                boolean tooClose = false;
                for (int[] other : planted) {
                    if (Math.abs(other[0] - spot[0]) + Math.abs(other[1] - spot[2]) < 4) {
                        tooClose = true;
                        break;
                    }
                }
                if (!tooClose && placeTree(region, random, tree, spot[0], spot[1] + 1, spot[2])) {
                    planted.add(new int[]{spot[0], spot[2]});
                }
                break;
            }
        }
    }

    /** Plantes : posées sur le sol de la zone, là où il y a de l'air au-dessus (pas sous un arbre). */
    private void plantPlants(BiomeDef def, Region region, Random random, List<int[]> spots) {
        if (spots == null || def.plants().isEmpty()) return;

        for (int[] spot : spots) {
            int x = spot[0];
            int y = spot[1] + 1;
            int z = spot[2];
            if (!region.contains(x, y, z) || !region.type(x, y, z).isAir()) continue;

            for (PlantDef plant : def.plants()) {
                if (random.nextDouble() >= plant.density()) continue;
                BlockData data = plant.block() != null ? manager.stateData(plant.block(), 0) : plant.material().createBlockData();
                if (data != null) region.set(x, y, z, data);
                break;
            }
        }
    }

    private boolean placeTree(Region region, Random random, TreeDef tree, int x, int y0, int z) {
        BlockData log = manager.stateData(tree.log(), 0);
        BlockData leaves = manager.stateData(tree.leaves(), 0);
        if (log == null || leaves == null) return false;

        int height = tree.minHeight() + random.nextInt(tree.maxHeight() - tree.minHeight() + 1);
        int radius = tree.leafRadius();
        if (y0 + height + radius >= region.maxY()) return false;

        // Le tronc doit avoir la place de pousser
        for (int i = 0; i < height; i++) {
            if (!region.contains(x, y0 + i, z) || !replaceable(region.type(x, y0 + i, z))) return false;
        }
        for (int i = 0; i < height; i++) {
            region.set(x, y0 + i, z, log);
        }

        int topY = y0 + height - 1;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz + dy * dy * 1.2 > radius * radius + 0.5) continue;

                    int lx = x + dx;
                    int ly = topY + dy;
                    int lz = z + dz;
                    if (ly <= y0 + 1 || !region.contains(lx, ly, lz)) continue;
                    if (!replaceable(region.type(lx, ly, lz))) continue;

                    region.set(lx, ly, lz, leaves);
                }
            }
        }
        return true;
    }

    private static boolean replaceable(Material type) {
        return type.isAir() || (!type.isSolid() && type != Material.WATER && type != Material.LAVA);
    }

    // ------------------------------------------------------------------ bruit

    /** Bruit de valeur lissé : forme des zones d'environ "scale" blocs, identiques pour une même graine. */
    private static double noise(long seed, String id, double x, double z, double scale) {
        double fx = x / scale;
        double fz = z / scale;
        int ix = (int) Math.floor(fx);
        int iz = (int) Math.floor(fz);

        double tx = fx - ix;
        double tz = fz - iz;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);

        long s = seed ^ ((long) id.hashCode() * 0x9E3779B97F4A7C15L);
        double v00 = hash(s, ix, iz);
        double v10 = hash(s, ix + 1, iz);
        double v01 = hash(s, ix, iz + 1);
        double v11 = hash(s, ix + 1, iz + 1);

        double a = v00 + (v10 - v00) * tx;
        double b = v01 + (v11 - v01) * tx;
        return a + (b - a) * tz;
    }

    private static double hash(long seed, int x, int z) {
        long h = seed ^ (x * 0x632BE59BD9B4E019L) ^ (z * 0x9E3779B97F4A7C15L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (h >>> 11) * (1.0 / (1L << 53));
    }
}

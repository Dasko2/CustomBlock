package fr.customblock;

import fr.customblock.api.CustomBlockBreakEvent;
import fr.customblock.api.CustomBlockPlaceEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;

import java.util.Iterator;
import java.util.List;

public final class BlockListener implements Listener {

    /** Bloc sans bonus d'outil (vitesse 1 pour tous les outils). */
    private static final Material NO_TOOL_REFERENCE = Material.GLASS;

    private final Main plugin;
    private final CustomBlockManager manager;
    private final ResourcePackManager packManager;
    private final RecipeManager recipeManager;
    private final NamespacedKey miningKey;

    public BlockListener(Main plugin, CustomBlockManager manager,
                         ResourcePackManager packManager, RecipeManager recipeManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.packManager = packManager;
        this.recipeManager = recipeManager;
        this.miningKey = new NamespacedKey(plugin, "mining_speed");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        clearMining(player);
        packManager.send(player);
        recipeManager.discover(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clearMining(event.getPlayer());
    }

    // ------------------------------------------------------------------ pose

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        CustomBlockManager.BlockDefinition def = manager.getDefinition(event.getItemInHand());
        if (def == null) return;

        CustomBlockPlaceEvent custom = new CustomBlockPlaceEvent(event.getPlayer(), event.getBlockPlaced(), def.id());
        Bukkit.getPluginManager().callEvent(custom);
        if (custom.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        // Dalles et escaliers : Minecraft pose et oriente lui-même le bloc vanilla réservé
        if (def.shape().equals("CUBE")) {
            int variant = def.axis() ? CustomBlockManager.axisVariant(event.getBlockPlaced(), event.getBlockAgainst())
                    : def.facing() ? CustomBlockManager.facingVariant(event.getPlayer().getFacing()) : 0;
            manager.placeBlock(event.getBlockPlaced(), def, variant);
        }
        feedback(event.getBlockPlaced(), def.id(), "place-sound", null);
    }

    /** Barrières invisibles : l'objet pose {@code wall_height} blocs BARRIER l'un sur l'autre (infranchissable en sautant). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlaceWall(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() != Material.BARRIER) return;
        org.bukkit.inventory.meta.ItemMeta meta = event.getItemInHand().getItemMeta();
        if (meta == null) return;
        Integer height = meta.getPersistentDataContainer().get(
                new NamespacedKey(plugin, "wall_height"), org.bukkit.persistence.PersistentDataType.INTEGER);
        if (height == null || height <= 1) return;

        Block base = event.getBlockPlaced();
        for (int i = 1; i < height; i++) {
            Block above = base.getRelative(0, i, 0);
            if (above.getY() >= above.getWorld().getMaxHeight() || !(above.isEmpty() || above.isReplaceable())) {
                event.setCancelled(true);
                event.getPlayer().sendActionBar(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage()
                        .deserialize("<red>Pas assez de place : il faut " + height + " blocs de haut."));
                return;
            }
        }
        for (int i = 1; i < height; i++) {
            base.getRelative(0, i, 0).setType(Material.BARRIER, false);
        }
    }

    /** Casser une barrière en créatif retire aussi les barrières empilées au-dessus (mur complet). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreakWall(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.BARRIER || event.getPlayer().getGameMode() != GameMode.CREATIVE) return;
        if (!event.getPlayer().isSneaking()) return;
        for (int i = 1; i < 8; i++) {
            Block above = block.getRelative(0, i, 0);
            if (above.getType() != Material.BARRIER) break;
            above.setType(Material.AIR, false);
        }
    }

    /**
     * Les blocs custom sont des blocs-note : on empêche leur état de changer (redstone, voisins)
     * et on coupe le son et l'accordage vanilla, sinon ils changeraient d'apparence.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.NOTE_BLOCK && manager.isCustomBlock(block)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNotePlay(NotePlayEvent event) {
        if (manager.isCustomBlock(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * Désactive l'accordage des blocs-note (clic droit) : il pourrait donner à un bloc-note vanilla
     * l'apparence d'un bloc custom. L'objet en main reste utilisable, on peut donc toujours poser un bloc contre.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clicked = event.getClickedBlock();
        if (clicked == null || clicked.getType() != Material.NOTE_BLOCK) return;
        if (!plugin.getConfig().getBoolean("disable-note-tuning", true)) return;

        event.setUseInteractedBlock(Event.Result.DENY);
    }

    // ------------------------------------------------------------------ minage

    /**
     * Dureté et outil personnalisés : on règle la vitesse de minage du joueur pendant qu'il casse ce bloc,
     * pour que le temps obtenu corresponde exactement à la dureté voulue.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        clearMining(player);

        CustomBlockManager.BlockDefinition def = manager.getDefinitionAt(event.getBlock());
        if (def == null || def.hardness() == null) return;

        double wanted = def.hardness();
        if (wanted == 0) {
            event.setInstaBreak(true);
            return;
        }

        AttributeInstance speed = breakSpeed(player);
        if (speed == null) return;

        double multiplier = miningMultiplier(def, wanted, player.getInventory().getItemInMainHand(), event.getBlock());
        speed.addTransientModifier(new AttributeModifier(
                miningKey,
                Math.max(multiplier, 0.0) - 1.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                EquipmentSlotGroup.ANY));
    }

    /**
     * Multiplicateur exact de la vitesse de minage. Le client calcule la progression ainsi :
     * vitesse / dureté_bloc-note(0.8) / 30 ; on veut vitesse_voulue / dureté_voulue / (30 ou 100).
     * Les vitesses viennent de Paper (BlockData#getDestroySpeed, enchantements inclus) ; hâte, fatigue et eau
     * s'appliquent des deux côtés et s'annulent.
     */
    private double miningMultiplier(CustomBlockManager.BlockDefinition def, double wanted, ItemStack tool, Block block) {
        if (wanted < 0) return 0.0; // incassable

        ItemStack hand = tool == null ? ItemStack.of(Material.AIR) : tool;

        // Ce que le client croit miner : le bloc-note, ou le bloc vanilla réservé (dalle, escalier)
        org.bukkit.block.data.BlockData clientData = block.getBlockData();
        double clientHardness = Math.max(0.0001, block.getType().getHardness());
        double clientSpeed = Math.max(0.0001, clientData.getDestroySpeed(hand, true));
        double clientDivisor = clientData.requiresCorrectToolForDrops() && !clientData.isPreferredTool(hand) ? 100.0 : 30.0;

        Material reference = def.tool() == null ? NO_TOOL_REFERENCE : referenceBlock(def.tool());
        double desiredSpeed = Math.max(0.0001, reference.createBlockData().getDestroySpeed(hand, true));

        double wantedDivisor = 30.0;
        if (def.tool() != null) {
            String toolName = hand.getType().name();
            boolean matches = toolName.endsWith("_" + def.tool());
            boolean tierOk = def.minTier() < 0 || CustomBlockManager.toolTier(toolName) >= def.minTier();
            if (!matches || !tierOk) {
                wantedDivisor = 100.0; // sans le bon outil, Minecraft divise la progression par 100 au lieu de 30
            }
        }

        return (desiredSpeed / clientSpeed) * (clientHardness / wanted) * (clientDivisor / wantedDivisor);
    }

    /** Bloc vanilla qui représente un type d'outil (sert à connaître la vitesse de l'outil sur ce type de bloc). */
    private static Material referenceBlock(String tool) {
        return switch (tool) {
            case "PICKAXE" -> Material.STONE;
            case "AXE" -> Material.OAK_PLANKS;
            case "SHOVEL" -> Material.DIRT;
            case "HOE" -> Material.HAY_BLOCK;
            default -> NO_TOOL_REFERENCE;
        };
    }

    @EventHandler
    public void onDamageAbort(BlockDamageAbortEvent event) {
        clearMining(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        clearMining(player);

        Block block = event.getBlock();
        CustomBlockManager.BlockDefinition def = manager.getDefinitionAt(block);
        if (def == null) return;

        CustomBlockBreakEvent custom = new CustomBlockBreakEvent(player, block, def.id(),
                player.getGameMode() != GameMode.CREATIVE);
        Bukkit.getPluginManager().callEvent(custom);
        if (custom.isCancelled()) {
            event.setCancelled(true);
            return;
        }

        if (manager.removeBlock(block, player.getInventory().getItemInMainHand(), custom.isDropItems())) {
            event.setDropItems(false);
            feedback(block, def.id(), "break-sound", "break-particle");
        }
    }

    // ------------------------------------------------------------------ explosions

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        handleExplosion(event.blockList());
    }

    private void handleExplosion(List<Block> blocks) {
        Iterator<Block> iterator = blocks.iterator();
        while (iterator.hasNext()) {
            Block block = iterator.next();
            if (manager.getDefinitionAt(block) == null) continue;

            if (manager.removeBlock(block, null, true)) {
                iterator.remove();
                block.setType(Material.AIR, false);
            }
        }
    }

    // ------------------------------------------------------------------ sons et particules

    /**
     * Joue le son et les particules configurés d'un bloc (en plus de ceux du bloc-note vanilla).
     * Un son est soit un son vanilla (block.amethyst_block.break), soit le nom d'un .ogg de plugins/CustomBlock/sounds/.
     */
    private void feedback(Block block, String id, String soundKey, String particleKey) {
        org.bukkit.configuration.ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("blocks." + id);
        if (cfg == null) return;
        org.bukkit.Location center = block.getLocation().add(0.5, 0.5, 0.5);

        String sound = cfg.getString(soundKey);
        if (sound != null && !sound.isBlank()) {
            String name = sound.trim().toLowerCase(java.util.Locale.ROOT);
            if (new java.io.File(plugin.getDataFolder(), "sounds/" + name + ".ogg").isFile()) {
                name = "customblock:" + name;
            }
            block.getWorld().playSound(center, name, org.bukkit.SoundCategory.BLOCKS, 1.0f, 1.0f);
        }

        String particle = particleKey == null ? null : cfg.getString(particleKey);
        if (particle != null && !particle.isBlank()) {
            String[] parts = particle.trim().split("\\s+");
            org.bukkit.Particle type = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(parts[0].toLowerCase(java.util.Locale.ROOT)));
            if (type == null) return;
            int count = 10;
            if (parts.length > 1) {
                try {
                    count = Math.max(1, Math.min(200, Integer.parseInt(parts[1])));
                } catch (NumberFormatException ignored) {
                    // garde 10
                }
            }
            try {
                block.getWorld().spawnParticle(type, center, count, 0.3, 0.3, 0.3, 0.02);
            } catch (IllegalArgumentException ex) {
                // particule qui exige des données supplémentaires (ex. DUST) : non gérée
            }
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private AttributeInstance breakSpeed(Player player) {
        Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("block_break_speed"));
        return attribute == null ? null : player.getAttribute(attribute);
    }

    private void clearMining(Player player) {
        AttributeInstance speed = breakSpeed(player);
        if (speed == null) return;

        for (AttributeModifier modifier : speed.getModifiers()) {
            if (modifier.getKey().equals(miningKey)) {
                speed.removeModifier(modifier);
            }
        }
    }
}

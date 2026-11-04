package fr.customblock;

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

    /** Dureté d'un bloc-note vanilla : référence pour calculer la vitesse de minage voulue. */
    private static final double NOTE_BLOCK_HARDNESS = 0.8;

    /** Bloc-note vanilla : ce que le client croit miner. */
    private static final org.bukkit.block.data.BlockData NOTE_REFERENCE = Material.NOTE_BLOCK.createBlockData();
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

        int variant = def.axis() ? CustomBlockManager.axisVariant(event.getBlockPlaced(), event.getBlockAgainst()) : 0;
        manager.placeBlock(event.getBlockPlaced(), def, variant);
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

        double multiplier = miningMultiplier(def, wanted, player.getInventory().getItemInMainHand());
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
    private double miningMultiplier(CustomBlockManager.BlockDefinition def, double wanted, ItemStack tool) {
        if (wanted < 0) return 0.0; // incassable

        ItemStack hand = tool == null ? ItemStack.of(Material.AIR) : tool;
        double clientSpeed = Math.max(0.0001, NOTE_REFERENCE.getDestroySpeed(hand, true));

        Material reference = def.tool() == null ? NO_TOOL_REFERENCE : referenceBlock(def.tool());
        double desiredSpeed = Math.max(0.0001, reference.createBlockData().getDestroySpeed(hand, true));

        double harvestFactor = 1.0;
        if (def.tool() != null) {
            String toolName = hand.getType().name();
            boolean matches = toolName.endsWith("_" + def.tool());
            boolean tierOk = def.minTier() < 0 || CustomBlockManager.toolTier(toolName) >= def.minTier();
            if (!matches || !tierOk) {
                harvestFactor = 30.0 / 100.0; // sans le bon outil, Minecraft divise la progression par 100 au lieu de 30
            }
        }

        return (desiredSpeed / clientSpeed) * (NOTE_BLOCK_HARDNESS / wanted) * harvestFactor;
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
        if (block.getType() != Material.NOTE_BLOCK) return;

        boolean drop = player.getGameMode() != GameMode.CREATIVE;
        if (manager.removeBlock(block, player.getInventory().getItemInMainHand(), drop)) {
            event.setDropItems(false);
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
            if (block.getType() != Material.NOTE_BLOCK) continue;

            if (manager.removeBlock(block, null, true)) {
                iterator.remove();
                block.setType(Material.AIR, false);
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

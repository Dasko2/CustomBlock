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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;

import java.util.Iterator;
import java.util.List;

public final class BlockListener implements Listener {

    private final CustomBlockManager manager;
    private final ResourcePackManager packManager;
    private final RecipeManager recipeManager;
    private final NamespacedKey miningKey;

    public BlockListener(Main plugin, CustomBlockManager manager,
                         ResourcePackManager packManager, RecipeManager recipeManager) {
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        CustomBlockManager.BlockDefinition def = manager.getDefinition(event.getItemInHand());
        if (def == null) return;

        manager.placeBlock(event.getBlockPlaced(), def);
    }

    /**
     * Dureté personnalisée : on ajuste la vitesse de minage du joueur pendant qu'il casse ce bloc,
     * en comparant la dureté voulue à celle du matériau réellement posé.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(BlockDamageEvent event) {
        Player player = event.getPlayer();
        clearMining(player);

        Block block = event.getBlock();
        if (!manager.isTracked(block.getType())) return;

        CustomBlockManager.BlockDefinition def = manager.getDefinitionAt(block);
        if (def == null || def.hardness() == null) return;

        double wanted = def.hardness();
        if (wanted == 0) {
            event.setInstaBreak(true);
            return;
        }

        double placed = def.placedMaterial().getHardness();
        if (placed <= 0) return;

        AttributeInstance speed = breakSpeed(player);
        if (speed == null) return;

        double ratio = wanted < 0 ? 0 : placed / wanted; // dureté négative = incassable
        speed.addTransientModifier(new AttributeModifier(
                miningKey,
                ratio - 1.0,
                AttributeModifier.Operation.MULTIPLY_SCALAR_1,
                EquipmentSlotGroup.ANY));
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
        if (!manager.isTracked(block.getType())) return;

        boolean drop = player.getGameMode() != GameMode.CREATIVE;
        if (manager.removeBlock(block, player.getInventory().getItemInMainHand(), drop)) {
            event.setDropItems(false);
        }
    }

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
            if (!manager.isTracked(block.getType())) continue;

            if (manager.removeBlock(block, null, true)) {
                iterator.remove();
                block.setType(Material.AIR, false);
            }
        }
    }

    // Les pistons ne doivent pas déplacer un bloc custom (l'entité d'affichage resterait en place)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block block : event.getBlocks()) {
            if (manager.isCustomBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block block : event.getBlocks()) {
            if (manager.isCustomBlock(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

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

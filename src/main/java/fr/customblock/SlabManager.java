package fr.customblock;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Slab;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

/**
 * Dalles custom (shape: SLAB). Aucun bloc vanilla obtenable n'est modifié : la collision, l'eau et la fusion en dalle
 * double viennent de la dalle de chêne pétrifié (introuvable en survie), que le pack rend invisible ; l'apparence est
 * un modèle affiché par une entité posée dans la case.
 */
public final class SlabManager implements Listener {

    private final Main plugin;
    private final CustomBlockManager manager;

    public SlabManager(Main plugin, CustomBlockManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        CustomBlockManager.BlockDefinition def = manager.getDefinition(event.getItemInHand());
        if (def == null || !def.shape().equals("SLAB")) return;

        Block placed = event.getBlockPlaced();
        if (placed.getType() != Material.PETRIFIED_OAK_SLAB || !(placed.getBlockData() instanceof Slab slab)) return;

        String existing = manager.slabIdAt(placed);
        if (slab.getType() == Slab.Type.DOUBLE) {
            // Deux dalles se fusionnent seulement si ce sont les mêmes
            if (existing == null || !existing.equals(def.id())) {
                event.setCancelled(true);
                return;
            }
            manager.slabEntities(placed).forEach(entity -> entity.remove());
        } else {
            manager.slabEntities(placed).forEach(entity -> entity.remove()); // reste éventuel
        }
        spawn(def, placed, slab.getType());
    }

    private void spawn(CustomBlockManager.BlockDefinition def, Block block, Slab.Type type) {
        ItemStack stack = manager.createItem(def, 1);
        ItemMeta meta = stack.getItemMeta();
        String model = switch (type) {
            case TOP -> def.id() + "_top";
            case DOUBLE -> def.id() + "_double";
            default -> def.id();
        };
        meta.setItemModel(new NamespacedKey(plugin, model));
        stack.setItemMeta(meta);

        block.getWorld().spawn(block.getLocation().add(0.5, 0.5, 0.5), ItemDisplay.class, entity -> {
            entity.setItemStack(stack);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            entity.setPersistent(true);
            entity.getPersistentDataContainer().set(manager.slabKey(), PersistentDataType.STRING, def.id());
        });
    }

    /** Un piston déplacerait le bloc de collision sans son modèle : on l'en empêche. */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (containsSlab(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (containsSlab(event.getBlocks())) event.setCancelled(true);
    }

    private boolean containsSlab(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.getType() == Material.PETRIFIED_OAK_SLAB && manager.slabIdAt(block) != null) return true;
        }
        return false;
    }
}

package fr.customblock;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Shulker;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
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
        if (def == null || !(def.shape().equals("SLAB") || def.shape().equals("STAIRS"))) return;

        if (def.shape().equals("STAIRS") && plugin.getConfig().getBoolean("client-mod.enabled", false)) return; // mode mod client
        Block placed = event.getBlockPlaced();
        if (placed.getType() != Material.PETRIFIED_OAK_SLAB || !(placed.getBlockData() instanceof Slab slab)) return;

        if (def.shape().equals("STAIRS")) {
            // Un escalier ne se fusionne pas en dalle double
            if (slab.getType() == Slab.Type.DOUBLE) {
                event.setCancelled(true);
                return;
            }
            manager.slabEntities(placed).forEach(Entity::remove);
            spawnStairs(def, placed, slab.getType(), event.getPlayer().getFacing());
            return;
        }

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
            entity.getPersistentDataContainer().set(manager.slabPosKey(), PersistentDataType.STRING, CustomBlockManager.posKey(block));
        });
    }

    /**
     * Escalier droit : la moitié basse (ou haute) est la collision de la dalle ; la marche du fond est faite de
     * deux boîtes de collision invisibles (shulkers réduits de moitié, sans IA). Le modèle est un affichage tourné.
     */
    private void spawnStairs(CustomBlockManager.BlockDefinition def, Block block, Slab.Type type, BlockFace facing) {
        BlockFace dir = switch (facing) {
            case NORTH, SOUTH, EAST, WEST -> facing;
            default -> BlockFace.NORTH;
        };
        boolean top = type == Slab.Type.TOP;
        String pos = CustomBlockManager.posKey(block);

        // Rotation du modèle d'escalier (marche haute à l'est par défaut) : y 0 est, 90 sud, 180 ouest, 270 nord
        int yaw = switch (dir) {
            case SOUTH -> 90;
            case WEST -> 180;
            case NORTH -> 270;
            default -> 0;
        };
        Quaternionf rotation = new Quaternionf().rotateY((float) -Math.toRadians(yaw));
        if (top) rotation.rotateX((float) Math.PI);

        ItemStack stack = manager.createItem(def, 1);
        block.getWorld().spawn(block.getLocation().add(0.5, 0.5, 0.5), ItemDisplay.class, entity -> {
            entity.setItemStack(stack);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            entity.setTransformation(new Transformation(new Vector3f(), rotation, new Vector3f(1, 1, 1), new Quaternionf()));
            entity.setPersistent(true);
            entity.getPersistentDataContainer().set(manager.slabKey(), PersistentDataType.STRING, def.id());
            entity.getPersistentDataContainer().set(manager.slabPosKey(), PersistentDataType.STRING, pos);
        });

        // Marche du fond : deux cubes de 0,5 côte à côte, sur la moitié du bloc du côté où l'escalier monte
        double dx = dir.getModX();
        double dz = dir.getModZ();
        double y = block.getY() + (top ? 0.0 : 0.5);
        for (int side = -1; side <= 1; side += 2) {
            double x = block.getX() + 0.5 + 0.25 * dx + 0.25 * side * Math.abs(dz);
            double z = block.getZ() + 0.5 + 0.25 * dz + 0.25 * side * Math.abs(dx);
            block.getWorld().spawn(new Location(block.getWorld(), x, y, z), Shulker.class, shulker -> {
                shulker.setAI(false);
                shulker.setInvisible(true);
                shulker.setSilent(true);
                shulker.setInvulnerable(true);
                shulker.setGravity(false);
                shulker.setPersistent(true);
                shulker.setRemoveWhenFarAway(false);
                shulker.setCollidable(true);
                shulker.setPeek(0f);
                var scale = shulker.getAttribute(Attribute.SCALE);
                if (scale != null) scale.setBaseValue(0.5);
                shulker.getPersistentDataContainer().set(manager.slabPosKey(), PersistentDataType.STRING, pos);
            });
        }
    }

    /** Les boîtes de collision ne peuvent pas être blessées. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Shulker
                && event.getEntity().getPersistentDataContainer().has(manager.slabPosKey(), PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
    }

    /** Frapper la marche (la boîte de collision) casse l'escalier : elle cache le bloc derrière elle. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Entity attacked = event.getAttacked();
        String pos = attacked.getPersistentDataContainer().get(manager.slabPosKey(), PersistentDataType.STRING);
        if (pos == null || !(attacked instanceof Shulker)) return;

        event.setCancelled(true);
        String[] parts = pos.split(",");
        try {
            Block block = attacked.getWorld().getBlockAt(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (block.getType() != Material.PETRIFIED_OAK_SLAB) {
                attacked.remove();
                return;
            }
            boolean drops = event.getPlayer().getGameMode() != org.bukkit.GameMode.CREATIVE;
            if (manager.removeBlock(block, event.getPlayer().getInventory().getItemInMainHand(), drops)) {
                block.setType(Material.AIR, true);
            }
        } catch (NumberFormatException ignored) {
            attacked.remove();
        }
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

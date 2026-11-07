package fr.customblock;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Blocs plus grands que 1x1x1 (clé "size" : [x, y, z]). Le bloc occupe un volume de cases invisibles (états de
 * bloc-note) : collision, minage et dureté sont ceux d'un vrai bloc. Une entité affiche le cube agrandi.
 * Casser une case casse tout le bloc.
 */
public final class BigBlockManager implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final CustomBlockManager manager;

    public BigBlockManager(CustomBlockManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        CustomBlockManager.BlockDefinition def = manager.getDefinition(event.getItemInHand());
        if (def == null || def.size() == null) return;

        int[] size = def.size();
        Block origin = event.getBlockPlaced();
        World world = origin.getWorld();

        // Le volume part de la case posée et s'étend vers +x, +y, +z : il doit être libre
        for (int dx = 0; dx < size[0]; dx++) {
            for (int dy = 0; dy < size[1]; dy++) {
                for (int dz = 0; dz < size[2]; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    Block cell = origin.getRelative(dx, dy, dz);
                    if (cell.getY() >= world.getMaxHeight()
                            || !(cell.isEmpty() || (cell.isReplaceable() && !cell.isLiquid()))) {
                        event.setCancelled(true);
                        event.getPlayer().sendActionBar(MM.deserialize("<red>Pas assez de place : il faut un volume libre de "
                                + size[0] + "x" + size[1] + "x" + size[2] + " vers l'est, le haut et le sud."));
                        return;
                    }
                }
            }
        }

        BlockData data = manager.stateData(def.id(), 0);
        if (data == null) return;
        for (int dx = 0; dx < size[0]; dx++) {
            for (int dy = 0; dy < size[1]; dy++) {
                for (int dz = 0; dz < size[2]; dz++) {
                    origin.getRelative(dx, dy, dz).setBlockData(data, false);
                }
            }
        }

        // Éclairage : les cases sont opaques, l'affichage prend la lumière des cases voisines au moment de la pose
        int blockLight = 0;
        int skyLight = 0;
        BlockFace[] faces = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
        for (int dx = 0; dx < size[0]; dx++) {
            for (int dy = 0; dy < size[1]; dy++) {
                for (int dz = 0; dz < size[2]; dz++) {
                    Block cell = origin.getRelative(dx, dy, dz);
                    for (BlockFace face : faces) {
                        Block neighbor = cell.getRelative(face);
                        if (inside(origin, size, neighbor)) continue;
                        blockLight = Math.max(blockLight, neighbor.getLightFromBlocks());
                        skyLight = Math.max(skyLight, neighbor.getLightFromSky());
                    }
                }
            }
        }
        final int block = blockLight;
        final int sky = skyLight;

        ItemStack stack = manager.createItem(def, 1);
        Location center = origin.getLocation().add(size[0] / 2.0, size[1] / 2.0, size[2] / 2.0);
        String stored = origin.getX() + "," + origin.getY() + "," + origin.getZ() + ","
                + size[0] + "," + size[1] + "," + size[2];
        world.spawn(center, ItemDisplay.class, entity -> {
            entity.setItemStack(stack);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            entity.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                    new Vector3f(size[0], size[1], size[2]), new Quaternionf()));
            entity.setBrightness(new Display.Brightness(block, sky));
            entity.setViewRange(2.0f);
            entity.setPersistent(true);
            entity.getPersistentDataContainer().set(manager.bigKey(), PersistentDataType.STRING, stored);
        });
    }

    private static boolean inside(Block origin, int[] size, Block b) {
        return b.getX() >= origin.getX() && b.getX() < origin.getX() + size[0]
                && b.getY() >= origin.getY() && b.getY() < origin.getY() + size[1]
                && b.getZ() >= origin.getZ() && b.getZ() < origin.getZ() + size[2];
    }

    /** Un piston déplacerait quelques cases seulement : on l'en empêche. */
    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (containsBig(event.getBlocks())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (containsBig(event.getBlocks())) event.setCancelled(true);
    }

    private boolean containsBig(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.getType() != Material.NOTE_BLOCK) continue;
            CustomBlockManager.BlockDefinition def = manager.getDefinitionAt(block);
            if (def != null && def.size() != null) return true;
        }
        return false;
    }
}

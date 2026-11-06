package fr.customblock;

import fr.customblock.api.CustomBlockBreakEvent;
import fr.customblock.api.CustomBlockPlaceEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;

/**
 * Plantes en croix (shape: CROSS) : un affichage d'objet (modèle en croix, éclairé comme l'air de sa case)
 * et une boîte de clic sans collision. Un coup suffit à la casser. Les entités sont enregistrées avec le chunk.
 */
public final class PlantManager implements Listener {

    private final Main plugin;
    private final CustomBlockManager manager;
    private final NamespacedKey plantKey;

    public PlantManager(Main plugin, CustomBlockManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.plantKey = new NamespacedKey(plugin, "plant_id");
    }

    // ------------------------------------------------------------------ pose

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) return;

        ItemStack item = event.getItem();
        CustomBlockManager.BlockDefinition def = manager.getDefinition(item);
        if (def == null || !def.shape().equals("CROSS")) return;

        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);

        Block clicked = event.getClickedBlock();
        BlockFace face = event.getBlockFace();
        if (clicked == null) return;
        Block target = clicked.isReplaceable() && !clicked.isLiquid() ? clicked : clicked.getRelative(face);

        Player player = event.getPlayer();
        if (!(target.isEmpty() || (target.isReplaceable() && !target.isLiquid()))
                || !target.getRelative(BlockFace.DOWN).isSolid()
                || !plantsAt(target).isEmpty()) {
            return;
        }

        CustomBlockPlaceEvent custom = new CustomBlockPlaceEvent(player, target, def.id());
        Bukkit.getPluginManager().callEvent(custom);
        if (custom.isCancelled()) return;

        spawn(def, target);
        player.swingMainHand();
        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private void spawn(CustomBlockManager.BlockDefinition def, Block block) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        ItemStack stack = manager.createItem(def, 1);

        ItemDisplay display = block.getWorld().spawn(center, ItemDisplay.class, entity -> {
            entity.setItemStack(stack);
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            entity.setPersistent(true);
            entity.getPersistentDataContainer().set(plantKey, PersistentDataType.STRING, def.id());
        });

        Location base = block.getLocation().add(0.5, 0.0, 0.5);
        block.getWorld().spawn(base, Interaction.class, entity -> {
            entity.setInteractionWidth(0.8f);
            entity.setInteractionHeight(0.9f);
            entity.setResponsive(true);
            entity.setPersistent(true);
            entity.getPersistentDataContainer().set(plantKey, PersistentDataType.STRING, def.id());
        });
        display.setPersistent(true);
    }

    // ------------------------------------------------------------------ casse

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (!(event.getAttacked() instanceof Interaction interaction)) return;
        String id = interaction.getPersistentDataContainer().get(plantKey, PersistentDataType.STRING);
        if (id == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        CustomBlockManager.BlockDefinition def = manager.getDefinition(id);
        Block block = interaction.getLocation().getBlock();

        if (def != null) {
            CustomBlockBreakEvent custom = new CustomBlockBreakEvent(player, block, id, player.getGameMode() != GameMode.CREATIVE);
            Bukkit.getPluginManager().callEvent(custom);
            if (custom.isCancelled()) return;
            if (custom.isDropItems()) {
                manager.dropItems(def, block.getLocation().add(0.5, 0.3, 0.5), player.getInventory().getItemInMainHand(), 1);
            }
        }
        remove(block);
    }

    /** Une plante dont le sol disparaît est retirée (et lâche ses drops). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGroundBroken(BlockBreakEvent event) {
        Block above = event.getBlock().getRelative(BlockFace.UP);
        for (Entity entity : plantsAt(above)) {
            String id = entity.getPersistentDataContainer().get(plantKey, PersistentDataType.STRING);
            CustomBlockManager.BlockDefinition def = id == null ? null : manager.getDefinition(id);
            if (def != null && entity instanceof Interaction && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
                manager.dropItems(def, above.getLocation().add(0.5, 0.3, 0.5), null, 1);
            }
        }
        remove(above);
    }

    // ------------------------------------------------------------------ utilitaires

    private List<Entity> plantsAt(Block block) {
        List<Entity> result = new ArrayList<>();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : block.getWorld().getNearbyEntities(center, 0.4, 0.6, 0.4)) {
            if (entity.getPersistentDataContainer().has(plantKey, PersistentDataType.STRING)) {
                result.add(entity);
            }
        }
        return result;
    }

    private void remove(Block block) {
        for (Entity entity : plantsAt(block)) {
            entity.remove();
        }
    }
}

package fr.customblock;

import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.PlayerStonecutterRecipeSelectEvent;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/**
 * Les objets et blocs custom gardent le matériau d'un objet vanilla (un rubis est un éclat d'améthyste).
 * Sans ce garde-fou, ils pourraient servir d'ingrédient aux crafts vanilla (un rubis dans une longue-vue).
 * Les crafts vanilla restent inchangés : seuls les objets custom en sont exclus.
 */
public final class CraftGuard implements Listener {

    private final NamespacedKey blockKey;
    private final NamespacedKey itemKey;
    private final String namespace;

    public CraftGuard(Main plugin) {
        this.blockKey = new NamespacedKey(plugin, "block_id");
        this.itemKey = new NamespacedKey(plugin, "item_id");
        this.namespace = plugin.getName().toLowerCase(java.util.Locale.ROOT);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent event) {
        if (event.getRecipe() instanceof Keyed keyed && keyed.getKey().getNamespace().equals(namespace)) return;

        for (ItemStack ingredient : event.getInventory().getMatrix()) {
            if (isCustom(ingredient)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    private boolean ours(Recipe recipe) {
        return recipe instanceof Keyed keyed && keyed.getKey().getNamespace().equals(namespace);
    }

    /** Four, haut-fourneau, fumoir, feu de camp : un objet custom ne se cuit que par tes crafts. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        if (isCustom(event.getSource()) && !ours(event.getRecipe())) {
            event.setCancelled(true);
        }
    }

    /** Table de forgeron. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onSmithing(PrepareSmithingEvent event) {
        if (ours(event.getInventory().getRecipe())) return;
        for (ItemStack ingredient : event.getInventory().getContents()) {
            if (ingredient != null && ingredient != event.getResult() && isCustom(ingredient)) {
                event.setResult(null);
                return;
            }
        }
    }

    /** Tailleur de pierre. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStonecutter(PlayerStonecutterRecipeSelectEvent event) {
        if (ours(event.getStonecuttingRecipe())) return;
        for (ItemStack ingredient : event.getStonecutterInventory().getContents()) {
            if (isCustom(ingredient)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean isCustom(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(blockKey, PersistentDataType.STRING) || pdc.has(itemKey, PersistentDataType.STRING);
    }
}

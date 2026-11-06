package fr.customblock;

import org.bukkit.Keyed;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
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

    private boolean isCustom(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        return pdc.has(blockKey, PersistentDataType.STRING) || pdc.has(itemKey, PersistentDataType.STRING);
    }
}

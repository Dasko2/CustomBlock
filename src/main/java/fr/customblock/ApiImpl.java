package fr.customblock;

import fr.customblock.api.CustomBlockAPI;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

final class ApiImpl implements CustomBlockAPI {

    private final Main plugin;
    private final CustomBlockManager manager;
    private final CustomItemManager itemManager;

    ApiImpl(Main plugin, CustomBlockManager manager, CustomItemManager itemManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.itemManager = itemManager;
    }

    @Override
    public boolean isCustomBlock(Block block) {
        return manager.isCustomBlock(block);
    }

    @Override
    public String getBlockId(Block block) {
        CustomBlockManager.BlockDefinition def = manager.getDefinitionAt(block);
        return def == null ? null : def.id();
    }

    @Override
    public String getItemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        var pdc = item.getItemMeta().getPersistentDataContainer();
        String id = pdc.get(new NamespacedKey(plugin, "block_id"), PersistentDataType.STRING);
        if (id == null) id = pdc.get(new NamespacedKey(plugin, "item_id"), PersistentDataType.STRING);
        return id;
    }

    @Override
    public ItemStack createItem(String id, int amount) {
        return manager.createStack(id, Math.max(1, amount));
    }

    @Override
    public boolean placeBlock(Block block, String id) {
        CustomBlockManager.BlockDefinition def = manager.getDefinition(id);
        if (def == null) return false;
        manager.placeBlock(block, def, 0);
        return true;
    }

    @Override
    public boolean breakBlock(Block block, boolean dropItems) {
        if (!manager.removeBlock(block, null, dropItems)) return false;
        block.setType(org.bukkit.Material.AIR, false);
        return true;
    }

    @Override
    public Set<String> getBlockIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(manager.getDefinitions().keySet()));
    }

    @Override
    public Set<String> getItemIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(itemManager.getDefinitions().keySet()));
    }
}

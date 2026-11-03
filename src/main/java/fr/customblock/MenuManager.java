package fr.customblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Menu /cb : liste tous les blocs et objets créés, classés par catégorie.
 * Un clic donne l'objet au joueur.
 */
public final class MenuManager implements Listener {

    public enum Category {
        BLOCKS("Blocs", Material.GRASS_BLOCK, 10),
        ARMOR("Armures", Material.DIAMOND_CHESTPLATE, 11),
        WEAPONS("Armes", Material.DIAMOND_SWORD, 12),
        TOOLS("Outils", Material.DIAMOND_PICKAXE, 13),
        OTHERS("Objets", Material.AMETHYST_SHARD, 14),
        ALL("Tout", Material.CHEST, 16);

        private final String label;
        private final Material icon;
        private final int slot;

        Category(String label, Material icon, int slot) {
            this.label = label;
            this.icon = icon;
            this.slot = slot;
        }
    }

    /** Porte l'état d'un menu ouvert : catégorie (null = menu principal), page et contenu des cases. */
    private static final class MenuHolder implements InventoryHolder {
        private final Category category;
        private final int page;
        private final String[] slotIds = new String[54];
        private Inventory inventory;

        private MenuHolder(Category category, int page) {
            this.category = category;
            this.page = page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final int PER_PAGE = 45;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_NEXT = 53;

    private final Main plugin;
    private final CustomBlockManager manager;
    private final CustomItemManager itemManager;

    public MenuManager(Main plugin, CustomBlockManager manager, CustomItemManager itemManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.itemManager = itemManager;
    }

    // ------------------------------------------------------------------ ouverture

    /** Ouvre le menu principal (choix de la catégorie). */
    public void openMain(Player player) {
        MenuHolder holder = new MenuHolder(null, 0);
        Inventory inventory = Bukkit.createInventory(holder, 27,
                MM.deserialize("<gradient:#55ffff:#5555ff><bold>CustomBlock</bold></gradient>"));
        holder.inventory = inventory;

        fill(inventory, 0, 27);
        for (Category category : Category.values()) {
            int count = idsFor(category).size();
            inventory.setItem(category.slot, button(
                    category.icon,
                    (count > 0 ? "<green>" : "<gray>") + category.label,
                    "<gray>" + count + " élément(s)",
                    count > 0 ? "<yellow>Clique pour ouvrir" : "<dark_gray>Rien à afficher"));
        }
        player.openInventory(inventory);
    }

    /** Ouvre la liste d'une catégorie à la page demandée (0 = première). */
    public void openList(Player player, Category category, int requestedPage) {
        List<String> ids = idsFor(category);
        int pages = Math.max(1, (ids.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        MenuHolder holder = new MenuHolder(category, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MM.deserialize("<dark_gray>CustomBlock » <white>" + category.label
                        + " <gray>(" + (page + 1) + "/" + pages + ")"));
        holder.inventory = inventory;

        fill(inventory, PER_PAGE, 54);

        if (ids.isEmpty()) {
            inventory.setItem(22, button(Material.PAPER, "<gray>Aucun élément", "<dark_gray>Ajoute-en dans config.yml"));
        }

        int start = page * PER_PAGE;
        for (int i = 0; i < PER_PAGE && start + i < ids.size(); i++) {
            String id = ids.get(start + i);
            ItemStack display = manager.createStack(id, 1);
            if (display == null) continue;

            ItemMeta meta = display.getItemMeta();
            List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.empty());
            lore.add(line("<dark_gray>ID : <gray>" + id));
            lore.add(line("<yellow>Clic <gray>: 1 exemplaire"));
            lore.add(line("<yellow>Maj + clic <gray>: pile complète"));
            meta.lore(lore);
            display.setItemMeta(meta);

            inventory.setItem(i, display);
            holder.slotIds[i] = id;
        }

        if (page > 0) {
            inventory.setItem(SLOT_PREV, button(Material.ARROW, "<yellow>Page précédente", "<gray>Page " + page + "/" + pages));
        }
        inventory.setItem(SLOT_BACK, button(Material.OAK_DOOR, "<red>Retour", "<gray>Menu principal"));
        if (page < pages - 1) {
            inventory.setItem(SLOT_NEXT, button(Material.ARROW, "<yellow>Page suivante", "<gray>Page " + (page + 2) + "/" + pages));
        }

        player.openInventory(inventory);
    }

    // ------------------------------------------------------------------ clics

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;

        // Rien ne peut être déplacé dans ou depuis le menu
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (!player.hasPermission("customblock.admin")) return;

        int slot = event.getSlot();

        if (holder.category == null) {
            for (Category category : Category.values()) {
                if (category.slot != slot) continue;

                if (idsFor(category).isEmpty()) {
                    player.sendMessage(MM.deserialize("<yellow>Aucun élément dans cette catégorie."));
                } else {
                    Bukkit.getScheduler().runTask(plugin, () -> openList(player, category, 0));
                }
                return;
            }
            return;
        }

        if (slot == SLOT_PREV && holder.page > 0) {
            Bukkit.getScheduler().runTask(plugin, () -> openList(player, holder.category, holder.page - 1));
            return;
        }
        if (slot == SLOT_NEXT && event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.ARROW) {
            Bukkit.getScheduler().runTask(plugin, () -> openList(player, holder.category, holder.page + 1));
            return;
        }
        if (slot == SLOT_BACK) {
            Bukkit.getScheduler().runTask(plugin, () -> openMain(player));
            return;
        }

        if (slot < 0 || slot >= PER_PAGE) return;
        String id = holder.slotIds[slot];
        if (id == null) return;

        ItemStack stack = manager.createStack(id, 1);
        if (stack == null) return;
        stack.setAmount(event.isShiftClick() ? stack.getMaxStackSize() : 1);

        player.getInventory().addItem(stack).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ contenu

    private List<String> idsFor(Category category) {
        List<String> ids = new ArrayList<>();

        if (category == Category.BLOCKS || category == Category.ALL) {
            ids.addAll(manager.getDefinitions().keySet());
        }
        for (Map.Entry<String, CustomItemManager.ItemDefinition> entry : itemManager.getDefinitions().entrySet()) {
            Category itemCategory = categoryOf(entry.getValue().material());
            if (category == Category.ALL || category == itemCategory) {
                ids.add(entry.getKey());
            }
        }
        return ids;
    }

    private static Category categoryOf(Material material) {
        String name = material.name();

        if (name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS")
                || name.endsWith("_BOOTS") || name.equals("TURTLE_HELMET") || name.equals("ELYTRA")
                || name.equals("SHIELD")) {
            return Category.ARMOR;
        }
        if (name.endsWith("_SWORD") || name.endsWith("_SPEAR") || name.equals("BOW") || name.equals("CROSSBOW")
                || name.equals("TRIDENT") || name.equals("MACE")) {
            return Category.WEAPONS;
        }
        if (name.endsWith("_PICKAXE") || name.endsWith("_AXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                || name.equals("SHEARS") || name.equals("FISHING_ROD") || name.equals("FLINT_AND_STEEL")) {
            return Category.TOOLS;
        }
        return Category.OTHERS;
    }

    // ------------------------------------------------------------------ utilitaires

    private static void fill(Inventory inventory, int from, int to) {
        ItemStack pane = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.empty());
        pane.setItemMeta(meta);

        for (int slot = from; slot < to; slot++) {
            inventory.setItem(slot, pane.clone());
        }
    }

    private static ItemStack button(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(line(name));

        List<Component> lines = new ArrayList<>();
        for (String text : lore) {
            lines.add(line(text));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }

    private static Component line(String miniMessage) {
        return MM.deserialize(miniMessage).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}

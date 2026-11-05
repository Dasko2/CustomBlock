package fr.customblock;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    /**
     * Porte l'état d'un menu ouvert : clé de catégorie (null = menu principal), page et contenu des cases.
     * Clés : nom d'une Category (BLOCKS...), "custom:<nom>" ou "search:<texte>".
     * recipeId non nul = écran d'aperçu d'un craft.
     */
    private static final class MenuHolder implements InventoryHolder {
        private final String key;
        private final int page;
        private final String[] slotIds = new String[54];
        private String recipeId;
        private int recipeIndex;
        private Inventory inventory;

        private MenuHolder(String key, int page) {
            this.key = key;
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
    private final RecipeManager recipeManager;

    public MenuManager(Main plugin, CustomBlockManager manager, CustomItemManager itemManager,
                       RecipeManager recipeManager) {
        this.plugin = plugin;
        this.manager = manager;
        this.itemManager = itemManager;
        this.recipeManager = recipeManager;
    }

    // ------------------------------------------------------------------ ouverture

    /** Ouvre le menu principal (choix de la catégorie). */
    public void openMain(Player player) {
        MenuHolder holder = new MenuHolder(null, 0);
        Inventory inventory = Bukkit.createInventory(holder, 36,
                MM.deserialize("<gradient:#55ffff:#5555ff><bold>CustomBlock</bold></gradient>"));
        holder.inventory = inventory;

        fill(inventory, 0, 36);
        for (Category category : Category.values()) {
            int count = idsFor(category.name()).size();
            inventory.setItem(category.slot, button(
                    category.icon,
                    (count > 0 ? "<green>" : "<gray>") + category.label,
                    "<gray>" + count + " élément(s)",
                    count > 0 ? "<yellow>Clique pour ouvrir" : "<dark_gray>Rien à afficher"));
        }

        // Catégories personnalisées (clé "category" d'un bloc ou objet), 7 au maximum
        int slot = 19;
        for (Map.Entry<String, CustomCategory> entry : customCategories().entrySet()) {
            if (slot > 25) break;
            int count = idsFor("custom:" + entry.getKey()).size();
            inventory.setItem(slot++, button(entry.getValue().icon(), "<green>" + entry.getValue().name(),
                    "<gray>" + count + " élément(s)", "<yellow>Clique pour ouvrir"));
        }
        player.openInventory(inventory);
    }

    /** Ouvre une liste filtrée sur un texte (id ou nom). */
    public void openSearch(Player player, String query) {
        openList(player, "search:" + query, 0);
    }

    /** Ouvre la liste d'une catégorie à la page demandée (0 = première). */
    public void openList(Player player, String key, int requestedPage) {
        List<String> ids = idsFor(key);
        int pages = Math.max(1, (ids.size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, pages - 1));

        MenuHolder holder = new MenuHolder(key, page);
        Inventory inventory = Bukkit.createInventory(holder, 54,
                MM.deserialize("<dark_gray>CustomBlock » <white>" + titleOf(key)
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
            if (!recipeManager.recipesFor(id).isEmpty()) {
                lore.add(line("<yellow>Clic droit <gray>: voir le craft"));
            }
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

    // ------------------------------------------------------------------ aperçu des crafts

    private static final int[] GRID = {10, 11, 12, 19, 20, 21, 28, 29, 30};
    private static final int SLOT_RECIPE_RESULT = 24;
    private static final int SLOT_RECIPE_STATION = 22;
    private static final int SLOT_RECIPE_NEXT = 40;
    private static final int SLOT_RECIPE_BACK = 44;

    /** Affiche le craft numéro {@code index} d'un objet. {@code from} = liste à rouvrir au retour. */
    private void openRecipe(Player player, String id, int index, MenuHolder from) {
        List<Recipe> recipes = recipeManager.recipesFor(id);
        if (recipes.isEmpty()) return;
        int current = Math.floorMod(index, recipes.size());
        Recipe recipe = recipes.get(current);

        MenuHolder holder = new MenuHolder(from.key, from.page);
        holder.recipeId = id;
        holder.recipeIndex = current;
        Inventory inventory = Bukkit.createInventory(holder, 45,
                MM.deserialize("<dark_gray>Craft » <white>" + id
                        + (recipes.size() > 1 ? " <gray>(" + (current + 1) + "/" + recipes.size() + ")" : "")));
        holder.inventory = inventory;
        fill(inventory, 0, 45);
        for (int slot : GRID) inventory.setItem(slot, null);
        inventory.setItem(SLOT_RECIPE_RESULT, null);
        inventory.setItem(SLOT_RECIPE_STATION, null);

        if (recipe instanceof ShapedRecipe shaped) {
            String[] shape = shaped.getShape();
            for (int row = 0; row < shape.length; row++) {
                for (int col = 0; col < shape[row].length(); col++) {
                    RecipeChoice choice = shaped.getChoiceMap().get(shape[row].charAt(col));
                    inventory.setItem(GRID[row * 3 + col], displayOf(choice));
                }
            }
            inventory.setItem(SLOT_RECIPE_STATION, button(Material.CRAFTING_TABLE, "<gray>Établi"));
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            List<RecipeChoice> choices = shapeless.getChoiceList();
            for (int i = 0; i < choices.size() && i < GRID.length; i++) {
                inventory.setItem(GRID[i], displayOf(choices.get(i)));
            }
            inventory.setItem(SLOT_RECIPE_STATION, button(Material.CRAFTING_TABLE, "<gray>Établi <dark_gray>(sans forme)"));
        } else if (recipe instanceof CookingRecipe<?> cooking) {
            inventory.setItem(GRID[4], displayOf(cooking.getInputChoice()));
            Material station = recipe instanceof BlastingRecipe ? Material.BLAST_FURNACE
                    : recipe instanceof SmokingRecipe ? Material.SMOKER
                    : recipe instanceof CampfireRecipe ? Material.CAMPFIRE : Material.FURNACE;
            inventory.setItem(SLOT_RECIPE_STATION, button(station, "<gray>" + prettify(station),
                    "<gray>Temps : <white>" + (cooking.getCookingTime() / 20.0) + " s",
                    "<gray>Expérience : <white>" + cooking.getExperience()));
        } else if (recipe instanceof StonecuttingRecipe stonecutting) {
            inventory.setItem(GRID[4], displayOf(stonecutting.getInputChoice()));
            inventory.setItem(SLOT_RECIPE_STATION, button(Material.STONECUTTER, "<gray>Tailleur de pierre"));
        } else if (recipe instanceof SmithingTransformRecipe smithing) {
            inventory.setItem(GRID[3], displayOf(smithing.getTemplate()));
            inventory.setItem(GRID[4], displayOf(smithing.getBase()));
            inventory.setItem(GRID[5], displayOf(smithing.getAddition()));
            inventory.setItem(SLOT_RECIPE_STATION, button(Material.SMITHING_TABLE, "<gray>Table de forgeron",
                    "<dark_gray>Modèle, base, ajout"));
        }

        ItemStack result = recipe.getResult().clone();
        inventory.setItem(SLOT_RECIPE_RESULT, result);

        if (recipes.size() > 1) {
            inventory.setItem(SLOT_RECIPE_NEXT, button(Material.ARROW, "<yellow>Craft suivant",
                    "<gray>" + (current + 1) + "/" + recipes.size()));
        }
        inventory.setItem(SLOT_RECIPE_BACK, button(Material.OAK_DOOR, "<red>Retour", "<gray>Retour à la liste"));
        player.openInventory(inventory);
    }

    private static ItemStack displayOf(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.ExactChoice exact && !exact.getChoices().isEmpty()) {
            return exact.getChoices().get(0).clone();
        }
        if (choice instanceof RecipeChoice.MaterialChoice material && !material.getChoices().isEmpty()) {
            return new ItemStack(material.getChoices().get(0));
        }
        return null;
    }

    private static String prettify(Material material) {
        String name = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
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

        // Écran d'aperçu d'un craft
        if (holder.recipeId != null) {
            if (slot == SLOT_RECIPE_BACK) {
                Bukkit.getScheduler().runTask(plugin, () -> openList(player, holder.key, holder.page));
            } else if (slot == SLOT_RECIPE_NEXT) {
                Bukkit.getScheduler().runTask(plugin, () -> openRecipe(player, holder.recipeId, holder.recipeIndex + 1, holder));
            }
            return;
        }

        if (holder.key == null) {
            for (Category category : Category.values()) {
                if (category.slot != slot) continue;
                openCategory(player, category.name());
                return;
            }
            int custom = 19;
            for (String name : customCategories().keySet()) {
                if (custom > 25) break;
                if (custom++ == slot) {
                    openCategory(player, "custom:" + name);
                    return;
                }
            }
            return;
        }

        if (slot == SLOT_PREV && holder.page > 0) {
            Bukkit.getScheduler().runTask(plugin, () -> openList(player, holder.key, holder.page - 1));
            return;
        }
        if (slot == SLOT_NEXT && event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.ARROW) {
            Bukkit.getScheduler().runTask(plugin, () -> openList(player, holder.key, holder.page + 1));
            return;
        }
        if (slot == SLOT_BACK) {
            Bukkit.getScheduler().runTask(plugin, () -> openMain(player));
            return;
        }

        if (slot < 0 || slot >= PER_PAGE) return;
        String id = holder.slotIds[slot];
        if (id == null) return;

        if (event.isRightClick()) {
            if (recipeManager.recipesFor(id).isEmpty()) {
                player.sendMessage(MM.deserialize("<yellow>Cet élément n'a pas de craft."));
            } else {
                Bukkit.getScheduler().runTask(plugin, () -> openRecipe(player, id, 0, holder));
            }
            return;
        }

        ItemStack stack = manager.createStack(id, 1);
        if (stack == null) return;
        stack.setAmount(event.isShiftClick() ? stack.getMaxStackSize() : 1);

        player.getInventory().addItem(stack).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.2f);
    }

    private void openCategory(Player player, String key) {
        if (idsFor(key).isEmpty()) {
            player.sendMessage(MM.deserialize("<yellow>Aucun élément dans cette catégorie."));
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> openList(player, key, 0));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ contenu

    private record CustomCategory(String name, Material icon) {
    }

    /** Catégories personnalisées : celles de la clé "category" des blocs et objets (nom et icône dans "categories"). */
    private Map<String, CustomCategory> customCategories() {
        Map<String, CustomCategory> result = new LinkedHashMap<>();
        ConfigurationSection defined = plugin.getConfig().getConfigurationSection("categories");
        for (String section : new String[]{"blocks", "items"}) {
            ConfigurationSection root = plugin.getConfig().getConfigurationSection(section);
            if (root == null) continue;
            for (String id : root.getKeys(false)) {
                String key = categoryKey(id);
                if (key == null || result.containsKey(key)) continue;

                String name = key;
                Material icon = Material.BOOK;
                ConfigurationSection def = defined == null ? null : defined.getConfigurationSection(key);
                if (def != null) {
                    name = def.getString("name", key);
                    Material matched = Material.matchMaterial(def.getString("icon", "BOOK"));
                    if (matched != null && matched.isItem() && !matched.isAir()) icon = matched;
                }
                result.put(key, new CustomCategory(name, icon));
            }
        }
        return result;
    }

    /** Catégorie personnalisée d'un bloc ou objet (en minuscules), ou null. */
    private String categoryKey(String id) {
        for (String section : new String[]{"blocks", "items"}) {
            String value = plugin.getConfig().getString(section + "." + id + ".category");
            if (value != null && !value.isBlank()) return value.trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private String titleOf(String key) {
        if (key.startsWith("search:")) return "Recherche : " + key.substring(7);
        if (key.startsWith("custom:")) {
            CustomCategory category = customCategories().get(key.substring(7));
            return category == null ? key.substring(7) : category.name();
        }
        try {
            return Category.valueOf(key).label;
        } catch (IllegalArgumentException ex) {
            return key;
        }
    }

    private List<String> idsFor(String key) {
        List<String> all = new ArrayList<>(manager.getDefinitions().keySet());
        all.addAll(itemManager.getDefinitions().keySet());

        List<String> ids = new ArrayList<>();
        if (key.startsWith("search:")) {
            String query = key.substring(7).toLowerCase(Locale.ROOT);
            for (String id : all) {
                String name = id.toLowerCase(Locale.ROOT);
                var block = manager.getDefinition(id);
                var item = itemManager.getDefinition(id);
                String display = block != null ? block.name() : item != null ? item.name() : "";
                name += " " + MM.stripTags(display).toLowerCase(Locale.ROOT);
                if (name.contains(query)) ids.add(id);
            }
            return ids;
        }
        if (key.startsWith("custom:")) {
            String wanted = key.substring(7);
            for (String id : all) {
                if (wanted.equals(categoryKey(id))) ids.add(id);
            }
            return ids;
        }

        Category category = Category.valueOf(key);
        if (category == Category.ALL) return all;
        for (String id : all) {
            if (categoryKey(id) != null) continue; // rangé dans sa catégorie personnalisée
            CustomItemManager.ItemDefinition item = itemManager.getDefinition(id);
            Category own = item == null ? Category.BLOCKS : categoryOf(item.material());
            if (own == category) ids.add(id);
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

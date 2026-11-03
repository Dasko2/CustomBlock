package fr.customblock;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Enregistre les crafts définis dans la sous-section "recipe" des blocs et des objets.
 */
public final class RecipeManager {

    private final Main plugin;
    private final CustomBlockManager manager;
    private final List<NamespacedKey> registered = new ArrayList<>();

    public RecipeManager(Main plugin, CustomBlockManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public int getRecipeCount() {
        return registered.size();
    }

    /** Supprime les anciens crafts puis enregistre ceux de la configuration. */
    public void reload() {
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();

        scan("blocks");
        scan("items");

        for (Player player : Bukkit.getOnlinePlayers()) {
            discover(player);
        }
    }

    /** Ajoute les crafts au livre de recettes du joueur. */
    public void discover(Player player) {
        if (!registered.isEmpty()) {
            player.discoverRecipes(registered);
        }
    }

    private void scan(String sectionName) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection(sectionName);
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;

            ConfigurationSection r = s.getConfigurationSection("recipe");
            if (r == null) continue;

            String id = key.toLowerCase(Locale.ROOT);
            ItemStack result = manager.createStack(id, Math.max(1, r.getInt("amount", 1)));
            if (result == null) {
                plugin.getLogger().warning("[" + id + "] craft ignoré : l'objet n'a pas pu être chargé.");
                continue;
            }

            NamespacedKey recipeKey = new NamespacedKey(plugin, "recipe_" + id);
            Recipe recipe = switch (r.getString("type", "shaped").toLowerCase(Locale.ROOT)) {
                case "shaped" -> buildShaped(id, r, recipeKey, result);
                case "shapeless" -> buildShapeless(id, r, recipeKey, result);
                default -> {
                    plugin.getLogger().warning("[" + id + "] type de craft inconnu (shaped ou shapeless) : ignoré.");
                    yield null;
                }
            };
            if (recipe == null) continue;

            try {
                if (Bukkit.addRecipe(recipe)) {
                    registered.add(recipeKey);
                }
            } catch (IllegalStateException | IllegalArgumentException ex) {
                plugin.getLogger().warning("[" + id + "] craft refusé : " + ex.getMessage());
            }
        }
    }

    private Recipe buildShaped(String id, ConfigurationSection r, NamespacedKey key, ItemStack result) {
        List<String> shape = r.getStringList("shape");
        if (shape.isEmpty() || shape.size() > 3) {
            plugin.getLogger().warning("[" + id + "] craft ignoré : 'shape' doit contenir 1 à 3 lignes.");
            return null;
        }
        int width = shape.get(0).length();
        for (String row : shape) {
            if (row.isEmpty() || row.length() > 3 || row.length() != width) {
                plugin.getLogger().warning("[" + id + "] craft ignoré : chaque ligne de 'shape' doit faire 1 à 3 caractères, toutes de même longueur.");
                return null;
            }
        }

        ConfigurationSection ingredients = r.getConfigurationSection("ingredients");
        if (ingredients == null) {
            plugin.getLogger().warning("[" + id + "] craft ignoré : section 'ingredients' manquante.");
            return null;
        }

        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape(shape.toArray(new String[0]));

        for (String row : shape) {
            for (char symbol : row.toCharArray()) {
                if (symbol == ' ') continue;

                String value = ingredients.getString(String.valueOf(symbol));
                if (value == null) {
                    plugin.getLogger().warning("[" + id + "] craft ignoré : l'ingrédient '" + symbol + "' n'est pas défini.");
                    return null;
                }
                RecipeChoice choice = choiceFor(id, value);
                if (choice == null) return null;
                recipe.setIngredient(symbol, choice);
            }
        }
        return recipe;
    }

    private Recipe buildShapeless(String id, ConfigurationSection r, NamespacedKey key, ItemStack result) {
        List<String> ingredients = r.getStringList("ingredients");
        if (ingredients.isEmpty() || ingredients.size() > 9) {
            plugin.getLogger().warning("[" + id + "] craft ignoré : 'ingredients' doit contenir 1 à 9 éléments.");
            return null;
        }

        ShapelessRecipe recipe = new ShapelessRecipe(key, result);
        for (String value : ingredients) {
            RecipeChoice choice = choiceFor(id, value);
            if (choice == null) return null;
            recipe.addIngredient(choice);
        }
        return recipe;
    }

    /** Ingrédient : ID d'objet/bloc custom (comparaison exacte) ou matériau vanilla. */
    private RecipeChoice choiceFor(String recipeId, String value) {
        String key = value.trim();

        if (manager.isCustomId(key)) {
            ItemStack stack = manager.createStack(key, 1);
            if (stack != null) return new RecipeChoice.ExactChoice(stack);
        }

        Material material = Material.matchMaterial(key);
        if (material != null && material.isItem() && !material.isAir()) {
            return new RecipeChoice.MaterialChoice(material);
        }

        plugin.getLogger().warning("[" + recipeId + "] craft ignoré : ingrédient inconnu '" + key + "'.");
        return null;
    }
}

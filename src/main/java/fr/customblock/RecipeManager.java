package fr.customblock;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BlastingRecipe;
import org.bukkit.inventory.CampfireRecipe;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.SmokingRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Enregistre les crafts définis dans la sous-section "recipe" des blocs et des objets.
 */
public final class RecipeManager {

    private final Main plugin;
    private final CustomBlockManager manager;
    private final List<NamespacedKey> registered = new ArrayList<>();
    /** Crafts enregistrés, par identifiant de l'objet obtenu (sert à l'aperçu du menu). */
    private final Map<String, List<Recipe>> byResult = new HashMap<>();

    public RecipeManager(Main plugin, CustomBlockManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    public int getRecipeCount() {
        return registered.size();
    }

    /** Crafts qui produisent cet objet ou bloc (liste vide s'il n'y en a pas). */
    public List<Recipe> recipesFor(String id) {
        List<Recipe> list = byResult.get(id);
        return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
    }

    /** Supprime les anciens crafts puis enregistre ceux de la configuration. */
    public void reload() {
        for (NamespacedKey key : registered) {
            Bukkit.removeRecipe(key);
        }
        registered.clear();
        byResult.clear();

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

            String id = key.toLowerCase(Locale.ROOT);

            // Un seul craft ("recipe") ou plusieurs ("recipes", une liste)
            List<ConfigurationSection> definitions = new ArrayList<>();
            ConfigurationSection single = s.getConfigurationSection("recipe");
            if (single != null) definitions.add(single);
            for (Map<?, ?> raw : s.getMapList("recipes")) {
                definitions.add(new MemoryConfiguration().createSection("recipe", raw));
            }

            for (int index = 0; index < definitions.size(); index++) {
                ConfigurationSection r = definitions.get(index);

                ItemStack result = manager.createStack(id, Math.max(1, r.getInt("amount", 1)));
                if (result == null) {
                    plugin.getLogger().warning("[" + id + "] craft ignoré : l'objet n'a pas pu être chargé.");
                    continue;
                }

                NamespacedKey recipeKey = new NamespacedKey(plugin, "recipe_" + id + (index == 0 ? "" : "_" + index));
                String type = r.getString("type", "shaped").toLowerCase(Locale.ROOT);
                Recipe recipe = switch (type) {
                    case "shaped" -> buildShaped(id, r, recipeKey, result);
                    case "shapeless" -> buildShapeless(id, r, recipeKey, result);
                    case "furnace", "smelting", "blasting", "blast_furnace", "smoking", "smoker", "campfire" ->
                            buildCooking(id, r, recipeKey, result, type);
                    case "stonecutter", "stonecutting" -> buildStonecutting(id, r, recipeKey, result);
                    case "smithing" -> buildSmithing(id, r, recipeKey, result);
                    default -> {
                        plugin.getLogger().warning("[" + id + "] type de craft inconnu : " + type
                                + " (shaped, shapeless, furnace, blasting, smoking, campfire, stonecutter, smithing).");
                        yield null;
                    }
                };
                if (recipe == null) continue;

                try {
                    if (Bukkit.addRecipe(recipe)) {
                        registered.add(recipeKey);
                        byResult.computeIfAbsent(id, k -> new ArrayList<>()).add(recipe);
                    }
                } catch (IllegalStateException | IllegalArgumentException ex) {
                    plugin.getLogger().warning("[" + id + "] craft refusé : " + ex.getMessage());
                }
            }
        }
    }

    private Recipe buildCooking(String id, ConfigurationSection r, NamespacedKey key, ItemStack result, String type) {
        String input = r.getString("input");
        if (input == null) {
            plugin.getLogger().warning("[" + id + "] craft ignoré : 'input' manquant.");
            return null;
        }
        RecipeChoice choice = choiceFor(id, input);
        if (choice == null) return null;

        float experience = (float) r.getDouble("experience", 0.0);
        int defaultTicks = switch (type) {
            case "blasting", "blast_furnace", "smoking", "smoker" -> 100;
            case "campfire" -> 600;
            default -> 200;
        };
        int ticks = r.contains("cooking-time")
                ? Math.max(1, EffectManager.parseTicks(String.valueOf(r.get("cooking-time")))) : defaultTicks;

        return switch (type) {
            case "blasting", "blast_furnace" -> new BlastingRecipe(key, result, choice, experience, ticks);
            case "smoking", "smoker" -> new SmokingRecipe(key, result, choice, experience, ticks);
            case "campfire" -> new CampfireRecipe(key, result, choice, experience, ticks);
            default -> new FurnaceRecipe(key, result, choice, experience, ticks);
        };
    }

    private Recipe buildStonecutting(String id, ConfigurationSection r, NamespacedKey key, ItemStack result) {
        String input = r.getString("input");
        if (input == null) {
            plugin.getLogger().warning("[" + id + "] craft ignoré : 'input' manquant.");
            return null;
        }
        RecipeChoice choice = choiceFor(id, input);
        return choice == null ? null : new StonecuttingRecipe(key, result, choice);
    }

    private Recipe buildSmithing(String id, ConfigurationSection r, NamespacedKey key, ItemStack result) {
        String template = r.getString("template");
        String base = r.getString("base");
        String addition = r.getString("addition");
        if (template == null || base == null || addition == null) {
            plugin.getLogger().warning("[" + id + "] craft ignoré : smithing demande 'template', 'base' et 'addition'.");
            return null;
        }
        RecipeChoice t = choiceFor(id, template);
        RecipeChoice b = choiceFor(id, base);
        RecipeChoice a = choiceFor(id, addition);
        if (t == null || b == null || a == null) return null;
        return new SmithingTransformRecipe(key, result, t, b, a);
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

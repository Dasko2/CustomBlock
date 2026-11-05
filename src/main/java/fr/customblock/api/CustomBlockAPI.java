package fr.customblock.api;

import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.Set;

/**
 * API publique de CustomBlock. Récupération depuis un autre plugin :
 * <pre>CustomBlockAPI api = Bukkit.getServicesManager().load(CustomBlockAPI.class);</pre>
 */
public interface CustomBlockAPI {

    /** true si ce bloc posé est un bloc CustomBlock. */
    boolean isCustomBlock(Block block);

    /** Identifiant du bloc CustomBlock posé à cet endroit, ou null. */
    String getBlockId(Block block);

    /** Identifiant (bloc ou objet CustomBlock) de cet item, ou null si c'est un item vanilla. */
    String getItemId(ItemStack item);

    /** Crée l'item d'un bloc ou objet custom (ou d'un matériau vanilla) ; null si l'ID est inconnu. */
    ItemStack createItem(String id, int amount);

    /** Pose un bloc custom à cet endroit (sans événement ni consommation d'item) ; false si l'ID est inconnu. */
    boolean placeBlock(Block block, String id);

    /** Retire un bloc custom, avec ou sans drops ; false si ce n'est pas un bloc custom. */
    boolean breakBlock(Block block, boolean dropItems);

    /** Identifiants de tous les blocs chargés. */
    Set<String> getBlockIds();

    /** Identifiants de tous les objets chargés. */
    Set<String> getItemIds();
}

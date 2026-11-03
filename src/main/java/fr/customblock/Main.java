package fr.customblock;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Main extends JavaPlugin implements TabExecutor {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<gradient:#55ffff:#5555ff><bold>CustomBlock</bold></gradient> <dark_gray>»</dark_gray> ";

    private CustomBlockManager manager;
    private CustomItemManager itemManager;
    private ResourcePackManager packManager;
    private RecipeManager recipeManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.itemManager = new CustomItemManager(this);
        this.manager = new CustomBlockManager(this, itemManager);
        this.packManager = new ResourcePackManager(this);
        this.recipeManager = new RecipeManager(this, manager);
        reloadAll();

        getServer().getPluginManager().registerEvents(
                new BlockListener(this, manager, packManager, recipeManager), this);

        var command = getCommand("customblock");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }

        getLogger().info(manager.getDefinitions().size() + " bloc(s) et "
                + itemManager.getDefinitions().size() + " objet(s) personnalisé(s) chargé(s).");
    }

    @Override
    public void onDisable() {
        if (packManager != null) {
            packManager.shutdown();
        }
    }

    /** Recharge blocs, objets et resource pack depuis la configuration. */
    private void reloadAll() {
        manager.load();
        itemManager.load(manager.getDefinitions().keySet());
        manager.validateDrops();
        recipeManager.reload();
        packManager.rebuild(manager.getDefinitions().values(), itemManager.getDefinitions().values());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            send(sender, "<gray>Usage : <white>/" + label + " <give|list|reload|pack>");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                reloadAll();
                packManager.sendToAll();
                send(sender, "<green>Configuration rechargée : <white><blocks></white> bloc(s), <white><items></white> objet(s), <white><recipes></white> craft(s), <white><textures></white> modèle(s) dans le pack.",
                        Placeholder.unparsed("blocks", String.valueOf(manager.getDefinitions().size())),
                        Placeholder.unparsed("items", String.valueOf(itemManager.getDefinitions().size())),
                        Placeholder.unparsed("recipes", String.valueOf(recipeManager.getRecipeCount())),
                        Placeholder.unparsed("textures", String.valueOf(packManager.getTextureCount())));
            }
            case "pack" -> {
                if (!packManager.isReady()) {
                    send(sender, "<yellow>Aucun resource pack n'est disponible (aucune texture valide ou pack désactivé).");
                    return true;
                }
                if (args.length >= 2 && args[1].equalsIgnoreCase("send")) {
                    packManager.sendToAll();
                    send(sender, "<green>Resource pack renvoyé à tous les joueurs.");
                } else if (sender instanceof Player player) {
                    packManager.send(player);
                    send(sender, "<green>Resource pack envoyé. <gray>(Fichier : plugins/CustomBlock/generated/resource-pack.zip)");
                } else {
                    send(sender, "<gray>URL : <white><url></white> <dark_gray>- fichier : plugins/CustomBlock/generated/resource-pack.zip",
                            Placeholder.unparsed("url", packManager.getUrl()));
                }
            }
            case "list" -> {
                if (manager.getDefinitions().isEmpty() && itemManager.getDefinitions().isEmpty()) {
                    send(sender, "<yellow>Aucun bloc ni objet n'est défini dans la configuration.");
                    return true;
                }
                send(sender, "<gray>Blocs et objets disponibles :");
                for (Map.Entry<String, CustomBlockManager.BlockDefinition> entry : manager.getDefinitions().entrySet()) {
                    CustomBlockManager.BlockDefinition def = entry.getValue();
                    sender.sendMessage(MM.deserialize(
                            "<dark_gray> - </dark_gray><white><id></white> <dark_gray>(bloc, </dark_gray><gray><material></gray><dark_gray>)</dark_gray>",
                            Placeholder.unparsed("id", def.id()),
                            Placeholder.unparsed("material", def.baseMaterial().name())));
                }
                for (Map.Entry<String, CustomItemManager.ItemDefinition> entry : itemManager.getDefinitions().entrySet()) {
                    CustomItemManager.ItemDefinition def = entry.getValue();
                    sender.sendMessage(MM.deserialize(
                            "<dark_gray> - </dark_gray><white><id></white> <dark_gray>(objet, </dark_gray><gray><material></gray><dark_gray>)</dark_gray>",
                            Placeholder.unparsed("id", def.id()),
                            Placeholder.unparsed("material", def.material().name())));
                }
            }
            case "give" -> {
                if (args.length < 3) {
                    send(sender, "<gray>Usage : <white>/" + label + " give <joueur> <id> [quantité]");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    send(sender, "<red>Joueur introuvable : <white><name>", Placeholder.unparsed("name", args[1]));
                    return true;
                }
                int amount = 1;
                if (args.length >= 4) {
                    try {
                        amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
                    } catch (NumberFormatException ex) {
                        send(sender, "<red>Quantité invalide : <white><value>", Placeholder.unparsed("value", args[3]));
                        return true;
                    }
                }
                ItemStack item = manager.createStack(args[2], amount);
                if (item == null) {
                    send(sender, "<red>Bloc ou objet inconnu : <white><id>", Placeholder.unparsed("id", args[2]));
                    return true;
                }
                target.getInventory().addItem(item).values()
                        .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                send(sender, "<green>Donné <white><amount>x</white> <white><id></white> à <white><player></white>.",
                        Placeholder.unparsed("amount", String.valueOf(amount)),
                        Placeholder.unparsed("id", args[2].toLowerCase(Locale.ROOT)),
                        Placeholder.unparsed("player", target.getName()));
            }
            default -> send(sender, "<red>Sous-commande inconnue. Utilise <white>give</white>, <white>list</white>, <white>reload</white> ou <white>pack</white>.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("give", "list", "reload", "pack")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(sub);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(p.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("pack")) {
            if ("send".startsWith(args[1].toLowerCase(Locale.ROOT))) result.add("send");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            for (String id : manager.getDefinitions().keySet()) {
                if (id.startsWith(prefix)) result.add(id);
            }
            for (String id : itemManager.getDefinitions().keySet()) {
                if (id.startsWith(prefix)) result.add(id);
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            result.addAll(List.of("1", "16", "64"));
        }
        return result;
    }

    private void send(CommandSender sender, String message, TagResolver... resolvers) {
        sender.sendMessage(MM.deserialize(PREFIX + message, resolvers));
    }
}

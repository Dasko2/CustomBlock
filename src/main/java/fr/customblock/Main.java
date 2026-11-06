package fr.customblock;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
    private MenuManager menuManager;
    private BiomeManager biomeManager;
    private OreManager oreManager;
    private AutoReloader autoReloader;
    private EffectManager effectManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.itemManager = new CustomItemManager(this);
        this.manager = new CustomBlockManager(this, itemManager);
        this.packManager = new ResourcePackManager(this);
        this.recipeManager = new RecipeManager(this, manager);
        this.biomeManager = new BiomeManager(this, manager);
        this.oreManager = new OreManager(this, manager);
        copyDefaultTextures();
        reloadAll();
        biomeManager.register();
        oreManager.register();

        this.menuManager = new MenuManager(this, manager, itemManager, recipeManager);

        getServer().getPluginManager().registerEvents(
                new BlockListener(this, manager, packManager, recipeManager), this);
        getServer().getPluginManager().registerEvents(menuManager, this);

        getServer().getPluginManager().registerEvents(new PlantManager(this, manager), this);
        getServer().getPluginManager().registerEvents(new SlabManager(this, manager), this);
        getServer().getPluginManager().registerEvents(new CraftGuard(this), this);

        this.effectManager = new EffectManager(this);
        effectManager.load();
        getServer().getPluginManager().registerEvents(effectManager, this);
        effectManager.start();

        getServer().getServicesManager().register(fr.customblock.api.CustomBlockAPI.class,
                new ApiImpl(this, manager, itemManager), this, org.bukkit.plugin.ServicePriority.Normal);

        this.autoReloader = new AutoReloader(this);
        autoReloader.start();

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
        if (autoReloader != null) {
            autoReloader.stop();
        }
        if (effectManager != null) {
            effectManager.stop();
        }
        if (biomeManager != null) {
            biomeManager.unregister();
        }
        if (oreManager != null) {
            oreManager.unregister();
        }
        if (packManager != null) {
            packManager.shutdown();
        }
    }

    /**
     * Au tout premier lancement, copie des textures d'exemple dans plugins/CustomBlock/textures/
     * pour que la configuration fournie fonctionne tout de suite. Rien n'est copié ensuite.
     */
    private void copyDefaultTextures() {
        File texturesDir = new File(getDataFolder(), "textures");
        if (texturesDir.exists()) return;

        if (!texturesDir.mkdirs()) {
            getLogger().warning("Impossible de créer le dossier " + texturesDir.getPath());
            return;
        }

        try (InputStream index = getResource("default-textures.txt")) {
            if (index == null) return;

            String list = new String(index.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : list.split("\\R")) {
                String name = line.trim();
                if (name.isEmpty()) continue;

                try (InputStream in = getResource("default-textures/" + name)) {
                    if (in != null) {
                        Files.copy(in, new File(texturesDir, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            getLogger().info("Textures d'exemple copiées dans " + texturesDir.getPath());
        } catch (IOException ex) {
            getLogger().warning("Copie des textures d'exemple impossible : " + ex.getMessage());
        }
    }

    /** Rechargement déclenché par la surveillance des fichiers : le pack n'est renvoyé que s'il a changé. */
    void autoReload() {
        String before = packManager.getSha1Hex();
        try {
            reloadConfig();
            reloadAll();
        } catch (RuntimeException ex) {
            getLogger().warning("Rechargement automatique échoué : " + ex.getMessage());
            return;
        }
        boolean changed = packManager.isReady() && !before.equals(packManager.getSha1Hex());
        if (changed) {
            packManager.sendToAll();
        }
        getLogger().info("Rechargement automatique : " + manager.getDefinitions().size() + " bloc(s), "
                + itemManager.getDefinitions().size() + " objet(s)" + (changed ? ", pack renvoyé." : "."));
        for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("customblock.admin")) {
                send(admin, "<green>Fichiers modifiés : rechargement automatique effectué"
                        + (changed ? " <gray>(pack renvoyé)" : "") + ".");
            }
        }
    }

    /** Recharge blocs, objets et resource pack depuis la configuration. */
    private void reloadAll() {
        manager.load();
        itemManager.load(manager.getDefinitions().keySet());
        manager.validateDrops();
        biomeManager.load();
        oreManager.load();
        recipeManager.reload();
        if (effectManager != null) {
            effectManager.load();
        }
        packManager.rebuild(manager.getDefinitions().values(), itemManager.getDefinitions().values(),
                manager.getRegistry());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                menuManager.openMain(player);
            } else {
                send(sender, "<gray>Usage : <white>/" + label + " <give|list|reload|pack|menu>");
            }
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "biome" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "<red>Cette commande est réservée aux joueurs.");
                    return true;
                }
                if (args.length < 2 || !args[1].equalsIgnoreCase("apply")) {
                    send(sender, "<gray>Usage : <white>/" + label + " biome apply [rayon en chunks]");
                    return true;
                }
                if (biomeManager.getBiomeCount() == 0) {
                    send(sender, "<yellow>Aucun biome valide n'est défini dans la section 'biomes' de la configuration.");
                    return true;
                }
                int radius = 3;
                if (args.length >= 3) {
                    try {
                        radius = Math.max(1, Math.min(6, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ex) {
                        send(sender, "<red>Rayon invalide : <white><value>", Placeholder.unparsed("value", args[2]));
                        return true;
                    }
                }
                int chunks = biomeManager.apply(player.getWorld(), player.getLocation().getBlockX() >> 4,
                        player.getLocation().getBlockZ() >> 4, radius);
                if (chunks == 0) {
                    send(sender, "<yellow>Aucun biome ne s'applique à ce monde (voir 'worlds' dans la configuration).");
                } else {
                    send(sender, "<green>Biomes appliqués sur <white><chunks></white> chunk(s) autour de toi.",
                            Placeholder.unparsed("chunks", String.valueOf(chunks)));
                }
            }
            case "ore" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "<red>Cette commande est réservée aux joueurs.");
                    return true;
                }
                if (args.length < 2 || !args[1].equalsIgnoreCase("apply")) {
                    send(sender, "<gray>Usage : <white>/" + label + " ore apply [rayon en chunks]");
                    return true;
                }
                if (oreManager.getOreCount() == 0) {
                    send(sender, "<yellow>Aucun minerai n'a de section 'generation' dans la configuration.");
                    return true;
                }
                int radius = 3;
                if (args.length >= 3) {
                    try {
                        radius = Math.max(1, Math.min(6, Integer.parseInt(args[2])));
                    } catch (NumberFormatException ex) {
                        send(sender, "<red>Rayon invalide : <white><value>", Placeholder.unparsed("value", args[2]));
                        return true;
                    }
                }
                int chunks = oreManager.apply(player.getWorld(), player.getLocation().getBlockX() >> 4,
                        player.getLocation().getBlockZ() >> 4, radius);
                send(sender, "<green>Minerais générés sur <white><chunks></white> chunk(s) autour de toi.",
                        Placeholder.unparsed("chunks", String.valueOf(chunks)));
            }
            case "search" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "<red>Cette commande est réservée aux joueurs.");
                    return true;
                }
                if (args.length < 2) {
                    send(sender, "<gray>Usage : <white>/" + label + " search <texte>");
                    return true;
                }
                menuManager.openSearch(player, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            }
            case "menu" -> {
                if (sender instanceof Player player) {
                    menuManager.openMain(player);
                } else {
                    send(sender, "<red>Cette commande est réservée aux joueurs.");
                }
            }
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
                    send(sender, "<gray>Usage : <white>/" + label + " give <joueur|@p|@a|@s|@r> <id> [quantité]");
                    return true;
                }
                List<Player> targets = new ArrayList<>();
                if (args[1].startsWith("@")) {
                    try {
                        for (Entity entity : Bukkit.selectEntities(sender, args[1])) {
                            if (entity instanceof Player p) targets.add(p);
                        }
                    } catch (IllegalArgumentException ex) {
                        send(sender, "<red>Sélecteur invalide : <white><value>", Placeholder.unparsed("value", args[1]));
                        return true;
                    }
                } else {
                    Player exact = Bukkit.getPlayerExact(args[1]);
                    if (exact != null) targets.add(exact);
                }
                if (targets.isEmpty()) {
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
                if (manager.createStack(args[2], 1) == null) {
                    send(sender, "<red>Bloc ou objet inconnu : <white><id>", Placeholder.unparsed("id", args[2]));
                    return true;
                }
                for (Player target : targets) {
                    ItemStack item = manager.createStack(args[2], amount);
                    target.getInventory().addItem(item).values()
                            .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                }
                send(sender, "<green>Donné <white><amount>x</white> <white><id></white> à <white><player></white>.",
                        Placeholder.unparsed("amount", String.valueOf(amount)),
                        Placeholder.unparsed("id", args[2].toLowerCase(Locale.ROOT)),
                        Placeholder.unparsed("player", targets.size() == 1 ? targets.get(0).getName()
                                : targets.size() + " joueurs"));
            }
            default -> send(sender, "<red>Sous-commande inconnue. Utilise <white>menu</white>, <white>give</white>, <white>list</white>, <white>reload</white> ou <white>pack</white>.");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("menu", "search", "give", "list", "reload", "pack", "biome", "ore")) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(sub);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (String selector : List.of("@p", "@a", "@s", "@r")) {
                if (selector.startsWith(args[1].toLowerCase(Locale.ROOT))) result.add(selector);
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    result.add(p.getName());
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ore")) {
            if ("apply".startsWith(args[1].toLowerCase(Locale.ROOT))) result.add("apply");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("biome")) {
            if ("apply".startsWith(args[1].toLowerCase(Locale.ROOT))) result.add("apply");
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

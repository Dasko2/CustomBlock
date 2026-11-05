package fr.customblock;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Effets de potion des objets (au coup, en main, porté) et bonus de sets d'armure, définis dans config.yml.
 */
public final class EffectManager implements Listener {

    private enum Trigger { HIT, HOLD, WEAR }

    private record EffectDef(Trigger trigger, PotionEffectType type, int ticks, int amplifier,
                             boolean onVictim, double chance, long cooldownMs) {
    }

    private record PassiveEffect(PotionEffectType type, int amplifier) {
    }

    private record SetDef(String id, List<String> pieces, TreeMap<Integer, List<PassiveEffect>> bonuses) {
    }

    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*([a-zA-Z]*)");
    /** Durée des effets passifs : rafraîchis chaque seconde, ils disparaissent juste après avoir retiré l'objet. */
    private static final int PASSIVE_TICKS = 60;

    private final Main plugin;
    private final NamespacedKey itemKey;
    private final Map<String, List<EffectDef>> itemEffects = new LinkedHashMap<>();
    private final List<SetDef> sets = new ArrayList<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private BukkitTask task;

    public EffectManager(Main plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "item_id");
    }

    public void start() {
        stop();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPassive, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    // ------------------------------------------------------------------ chargement

    public void load() {
        itemEffects.clear();
        sets.clear();
        cooldowns.clear();

        ConfigurationSection items = plugin.getConfig().getConfigurationSection("items");
        if (items != null) {
            for (String key : items.getKeys(false)) {
                ConfigurationSection item = items.getConfigurationSection(key);
                if (item == null) continue;
                String id = key.toLowerCase(Locale.ROOT);
                List<EffectDef> list = new ArrayList<>();
                for (Map<?, ?> raw : item.getMapList("effects")) {
                    EffectDef def = parseEffect(id, raw);
                    if (def != null) list.add(def);
                }
                if (!list.isEmpty()) itemEffects.put(id, list);
            }
        }

        ConfigurationSection setSection = plugin.getConfig().getConfigurationSection("sets");
        if (setSection != null) {
            for (String key : setSection.getKeys(false)) {
                ConfigurationSection s = setSection.getConfigurationSection(key);
                if (s == null) continue;
                List<String> pieces = new ArrayList<>();
                for (String piece : s.getStringList("pieces")) pieces.add(piece.toLowerCase(Locale.ROOT));
                if (pieces.isEmpty()) {
                    plugin.getLogger().warning("[set " + key + "] aucune pièce dans 'pieces' : ignoré.");
                    continue;
                }
                TreeMap<Integer, List<PassiveEffect>> bonuses = new TreeMap<>();
                ConfigurationSection bonusSection = s.getConfigurationSection("bonuses");
                if (bonusSection != null) {
                    for (String count : bonusSection.getKeys(false)) {
                        int needed;
                        try {
                            needed = Integer.parseInt(count);
                        } catch (NumberFormatException ex) {
                            plugin.getLogger().warning("[set " + key + "] nombre de pièces invalide : " + count);
                            continue;
                        }
                        List<PassiveEffect> effects = new ArrayList<>();
                        for (Map<?, ?> raw : bonusSection.getMapList(count)) {
                            PotionEffectType type = effectType(String.valueOf(raw.get("effect")));
                            if (type == null) {
                                plugin.getLogger().warning("[set " + key + "] effet inconnu : " + raw.get("effect"));
                                continue;
                            }
                            effects.add(new PassiveEffect(type, Math.max(0, intOf(raw.get("level"), 1) - 1)));
                        }
                        bonuses.put(needed, effects);
                    }
                }
                sets.add(new SetDef(key.toLowerCase(Locale.ROOT), pieces, bonuses));
            }
        }
    }

    private EffectDef parseEffect(String itemId, Map<?, ?> raw) {
        Trigger trigger;
        try {
            trigger = Trigger.valueOf(String.valueOf(raw.get("trigger")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("[" + itemId + "] trigger invalide (HIT, HOLD ou WEAR) : " + raw.get("trigger"));
            return null;
        }
        PotionEffectType type = effectType(String.valueOf(raw.get("effect")));
        if (type == null) {
            plugin.getLogger().warning("[" + itemId + "] effet inconnu : " + raw.get("effect"));
            return null;
        }
        int ticks = raw.containsKey("duration") ? parseTicks(String.valueOf(raw.get("duration"))) : 100;
        int amplifier = Math.max(0, intOf(raw.get("level"), 1) - 1);
        String target = String.valueOf(raw.get("target")).toUpperCase(Locale.ROOT);
        boolean onVictim = trigger == Trigger.HIT && !target.equals("SELF");
        double chance = raw.get("chance") instanceof Number n ? n.doubleValue() : 100.0;
        long cooldown = raw.containsKey("cooldown") ? parseTicks(String.valueOf(raw.get("cooldown"))) * 50L : 0L;
        return new EffectDef(trigger, type, Math.max(1, ticks), amplifier, onVictim, chance, cooldown);
    }

    private static PotionEffectType effectType(String name) {
        String key = name.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        NamespacedKey parsed = key.contains(":") ? NamespacedKey.fromString(key) : NamespacedKey.minecraft(key);
        return parsed == null ? null : Registry.EFFECT.get(parsed);
    }

    private static int intOf(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** "5s", "1.5s", "2m", "100t" ou un nombre seul (secondes) → ticks. */
    static int parseTicks(String text) {
        Matcher m = DURATION.matcher(text.trim());
        if (!m.matches()) return 0;
        double value = Double.parseDouble(m.group(1));
        return (int) Math.round(switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "t" -> value;
            case "m" -> value * 1200;
            default -> value * 20;
        });
    }

    // ------------------------------------------------------------------ effets au coup

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;

        String id = itemId(player.getInventory().getItemInMainHand());
        List<EffectDef> list = id == null ? null : itemEffects.get(id);
        if (list == null) return;

        long now = System.currentTimeMillis();
        for (int i = 0; i < list.size(); i++) {
            EffectDef def = list.get(i);
            if (def.trigger() != Trigger.HIT) continue;
            if (def.chance() < 100.0 && Math.random() * 100.0 >= def.chance()) continue;

            if (def.cooldownMs() > 0) {
                String key = player.getUniqueId() + ":" + id + ":" + i;
                Long until = cooldowns.get(key);
                if (until != null && until > now) continue;
                cooldowns.put(key, now + def.cooldownMs());
            }
            LivingEntity target = def.onVictim() ? victim : player;
            target.addPotionEffect(new PotionEffect(def.type(), def.ticks(), def.amplifier()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        cooldowns.keySet().removeIf(key -> key.startsWith(uuid.toString()));
    }

    // ------------------------------------------------------------------ effets passifs et sets

    private void tickPassive() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<PotionEffectType, Integer> wanted = new HashMap<>();

            String held = itemId(player.getInventory().getItemInMainHand());
            collect(held, Trigger.HOLD, wanted);

            List<String> worn = new ArrayList<>();
            for (ItemStack armor : player.getInventory().getArmorContents()) {
                String id = itemId(armor);
                if (id == null) continue;
                worn.add(id);
                collect(id, Trigger.WEAR, wanted);
            }

            for (SetDef set : sets) {
                int count = 0;
                for (String piece : set.pieces()) {
                    if (worn.contains(piece)) count++;
                }
                for (Map.Entry<Integer, List<PassiveEffect>> bonus : set.bonuses().entrySet()) {
                    if (count < bonus.getKey()) continue;
                    for (PassiveEffect effect : bonus.getValue()) {
                        wanted.merge(effect.type(), effect.amplifier(), Math::max);
                    }
                }
            }

            for (Map.Entry<PotionEffectType, Integer> entry : wanted.entrySet()) {
                // ambiant = false, particules cachées, icône visible
                player.addPotionEffect(new PotionEffect(entry.getKey(), PASSIVE_TICKS, entry.getValue(), false, false, true));
            }
        }
    }

    private void collect(String itemId, Trigger trigger, Map<PotionEffectType, Integer> wanted) {
        List<EffectDef> list = itemId == null ? null : itemEffects.get(itemId);
        if (list == null) return;
        for (EffectDef def : list) {
            if (def.trigger() == trigger) wanted.merge(def.type(), def.amplifier(), Math::max);
        }
    }

    private String itemId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }
}

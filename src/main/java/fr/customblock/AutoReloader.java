package fr.customblock;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Surveille config.yml et le dossier textures/ : à chaque modification, recharge le plugin
 * (avec un délai pour regrouper plusieurs écritures de fichiers).
 */
final class AutoReloader {

    private final Main plugin;
    private final AtomicBoolean pending = new AtomicBoolean(false);
    private WatchService watcher;
    private Thread thread;

    AutoReloader(Main plugin) {
        this.plugin = plugin;
    }

    void start() {
        if (!plugin.getConfig().getBoolean("auto-reload", true)) return;

        File data = plugin.getDataFolder();
        File textures = new File(data, "textures");
        File sounds = new File(data, "sounds");
        try {
            watcher = FileSystems.getDefault().newWatchService();
            register(data.toPath());
            if (textures.isDirectory()) register(textures.toPath());
            if (sounds.isDirectory()) register(sounds.toPath());
        } catch (IOException | UnsupportedOperationException ex) {
            plugin.getLogger().warning("Rechargement automatique indisponible : " + ex.getMessage());
            stop();
            return;
        }

        thread = new Thread(this::loop, "CustomBlock-AutoReload");
        thread.setDaemon(true);
        thread.start();
    }

    private void register(Path dir) throws IOException {
        dir.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
    }

    private void loop() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key = watcher.take();
                boolean relevant = false;
                Path dir = (Path) key.watchable();
                boolean isRoot = dir.toFile().equals(plugin.getDataFolder());
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object context = event.context();
                    if (!(context instanceof Path name)) continue;
                    String file = name.toString().toLowerCase(Locale.ROOT);
                    if (isRoot ? file.equals("config.yml") : (file.endsWith(".png") || file.endsWith(".ogg"))) {
                        relevant = true;
                    }
                }
                key.reset();
                if (relevant && pending.compareAndSet(false, true)) {
                    // ~2 s de délai : laisse le temps de finir de copier plusieurs fichiers
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        pending.set(false);
                        plugin.autoReload();
                    }, 40L);
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException ignored) {
            // arrêt du plugin
        } catch (IllegalStateException ex) {
            // le plugin est en cours d'arrêt : le scheduler refuse de nouvelles tâches
        }
    }

    void stop() {
        if (thread != null) thread.interrupt();
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException ignored) {
                // rien à faire
            }
        }
    }
}

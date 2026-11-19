package org.Cry_Legende.sHEggDragonBoss.model;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Modèles 3D des boss : lecture des fichiers (dans le jar, ou models/&lt;nom&gt;.txt du dossier du plugin pour en essayer
 * un autre), un {@link BossModel} par boss affiché, et le suivi des joueurs qui voient chaque boss.
 * Demande le plugin packetevents ; sans lui, aucun modèle n'est créé et les boss gardent leur apparence vanilla.
 */
public final class ModelManager implements Listener {

    private final JavaPlugin plugin;
    private final boolean available;
    private final Map<String, CompletableFuture<ModelFile>> files = new ConcurrentHashMap<>();
    private final Map<Integer, BossModel> byEntity = new ConcurrentHashMap<>();

    public ModelManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.available = Bukkit.getPluginManager().isPluginEnabled("packetevents");
        if (available) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
        } else {
            plugin.getLogger().info("packetevents absent : les boss gardent leur apparence vanilla.");
        }
    }

    /** Lit un modèle en arrière-plan (au démarrage), pour qu'il soit prêt quand un boss apparaît. */
    public void preload(String name) {
        if (available) {
            file(name);
        }
    }

    private CompletableFuture<ModelFile> file(String name) {
        return files.computeIfAbsent(name, key -> CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            ModelFile file = ModelFile.read(key, read(key));
            plugin.getLogger().info("Modèle " + key + " : " + file.parts().size() + " pièces, " + file.states().size()
                + " états, lu en " + (System.nanoTime() - start) / 1_000_000 + " ms.");
            return file;
        }).exceptionally(error -> {
            plugin.getLogger().log(Level.WARNING, "Modèle " + key + " illisible, le boss garde son apparence vanilla : " + error.getMessage());
            return null;
        }));
    }

    private String read(String name) {
        File override = new File(plugin.getDataFolder(), "models/" + name + ".txt");
        try {
            if (override.isFile()) {
                return Files.readString(override.toPath(), StandardCharsets.UTF_8);
            }
            try (InputStream in = plugin.getResource("models/" + name + ".txt")) {
                if (in == null) {
                    throw new IllegalStateException("models/" + name + ".txt absent du jar");
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /**
     * Habille un boss de son modèle : il devient invisible, sa taille (hitbox, portée) devient entityScale, et le
     * modèle s'affiche à scale chez ceux qui le voient. Rend null si le modèle n'est pas disponible (packetevents absent,
     * fichier illisible ou pas encore lu) : le boss garde alors son apparence vanilla.
     */
    public BossModel attach(LivingEntity entity, String name, double scale, double entityScale, float facing) {
        if (!available) {
            return null;
        }
        ModelFile file = file(name).getNow(null);
        if (file == null) {
            return null;
        }
        detach(entity);
        AttributeInstance size = entity.getAttribute(Attribute.SCALE);
        if (size != null) {
            size.setBaseValue(entityScale);
        }
        entity.setInvisible(true);
        BossModel model = new BossModel(entity, file, (float) scale, facing);
        byEntity.put(entity.getEntityId(), model);
        model.start();
        return model;
    }

    /** Retire le modèle d'un boss ; s'il est encore dans le monde, il retrouve son apparence vanilla. */
    public void detach(LivingEntity entity) {
        BossModel model = byEntity.remove(entity.getEntityId());
        if (model != null) {
            model.destroy();
        }
        if (entity.isValid() && !entity.isDead()) {
            entity.setInvisible(false);
            AttributeInstance size = entity.getAttribute(Attribute.SCALE);
            if (size != null) {
                size.setBaseValue(size.getDefaultValue());
            }
        }
    }

    /** Arrêt du plugin : plus aucun modèle chez personne, les boss encore là redeviennent visibles. */
    public void shutdown() {
        for (BossModel model : new ArrayList<>(byEntity.values())) {
            detach(model.entity());
        }
        HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrack(PlayerTrackEntityEvent event) {
        BossModel model = byEntity.get(event.getEntity().getEntityId());
        if (model != null) {
            model.track(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntrack(PlayerUntrackEntityEvent event) {
        BossModel model = byEntity.get(event.getEntity().getEntityId());
        if (model != null) {
            model.untrack(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        for (BossModel model : byEntity.values()) {
            model.forget(event.getPlayer().getUniqueId());
        }
    }
}

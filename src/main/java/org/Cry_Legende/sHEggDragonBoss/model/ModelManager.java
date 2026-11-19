package org.Cry_Legende.sHEggDragonBoss.model;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntitySoundEffect;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSoundEffect;
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
import java.util.List;
import java.util.UUID;
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
    private PacketListenerCommon veil;

    /** Données du Creaking (26.3) : actif (ses yeux brillent) et en train de s'effondrer (ses yeux clignotent). */
    private static final int CREAKING_ACTIVE = 17;
    private static final int CREAKING_TEARING_DOWN = 18;
    private static final String CREAKING_AMBIENT = "minecraft:entity.creaking.ambient";

    public ModelManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.available = Bukkit.getPluginManager().isPluginEnabled("packetevents");
        if (available) {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            veil = PacketEvents.getAPI().getEventManager().registerListener(new Veil());
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
        if (veil != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(veil);
            veil = null;
        }
    }

    /** Ce joueur voit-il un boss habillé ? (fil réseau) */
    private boolean watchesBoss(UUID viewer) {
        for (BossModel model : byEntity.values()) {
            if (model.shownTo(viewer)) return true;
        }
        return false;
    }

    /**
     * Le vrai Creaking, invisible sous le modèle, ne doit ni se voir ni s'entendre : le client dessine ses yeux lumineux même
     * quand il est invisible (ils brillent tant qu'il est « actif »), et il grince à intervalles quand aucun joueur ne le
     * cible. Chez les joueurs qui voient un boss habillé : « actif » et « s'effondre » sont envoyés à faux (rien d'autre ne
     * s'en sert côté client), le bruit d'ambiance est retiré. Les autres paquets ne sont pas lus.
     */
    private final class Veil extends PacketListenerAbstract {
        private Veil() {
            super(PacketListenerPriority.NORMAL);
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            Object type = event.getPacketType();
            if (type != PacketType.Play.Server.ENTITY_METADATA && type != PacketType.Play.Server.SOUND_EFFECT
                && type != PacketType.Play.Server.ENTITY_SOUND_EFFECT) return;
            if (byEntity.isEmpty() || event.getUser() == null || event.getUser().getUUID() == null
                || !watchesBoss(event.getUser().getUUID())) return;
            if (type == PacketType.Play.Server.ENTITY_METADATA) {
                WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
                if (!byEntity.containsKey(packet.getEntityId())) return;
                List<EntityData<?>> data = packet.getEntityMetadata();
                boolean changed = false;
                for (int i = 0; i < data.size(); i++) {
                    EntityData<?> entry = data.get(i);
                    if ((entry.getIndex() == CREAKING_ACTIVE || entry.getIndex() == CREAKING_TEARING_DOWN) && Boolean.TRUE.equals(entry.getValue())) {
                        data.set(i, new EntityData<>(entry.getIndex(), EntityDataTypes.BOOLEAN, false));
                        changed = true;
                    }
                }
                if (changed) {
                    packet.setEntityMetadata(data);
                    event.markForReEncode(true);
                }
            } else if (type == PacketType.Play.Server.SOUND_EFFECT) {
                WrapperPlayServerSoundEffect packet = new WrapperPlayServerSoundEffect(event);
                if (!CREAKING_AMBIENT.equals(String.valueOf(packet.getSound().getSoundId()))) return;
                Vector3d at = packet.getPosition();
                for (BossModel model : byEntity.values()) {
                    if (model.near(at.getX(), at.getY(), at.getZ(), 2.0)) {
                        event.setCancelled(true);
                        return;
                    }
                }
            } else {
                WrapperPlayServerEntitySoundEffect packet = new WrapperPlayServerEntitySoundEffect(event);
                if (byEntity.containsKey(packet.getEntityId()) && CREAKING_AMBIENT.equals(String.valueOf(packet.getSound().getSoundId()))) {
                    event.setCancelled(true);
                }
            }
        }
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

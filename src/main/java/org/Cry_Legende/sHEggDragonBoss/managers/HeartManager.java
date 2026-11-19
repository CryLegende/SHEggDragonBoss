package org.Cry_Legende.sHEggDragonBoss.managers;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerCommon;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.boss.HeartOwner;
import org.Cry_Legende.sHEggDragonBoss.config.CreakingSettings;
import org.Cry_Legende.sHEggDragonBoss.model.Packets;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.CreakingHeart;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Les cœurs du Creaking : pendant une phase d'invulnérabilité, des cœurs éveillés posés au sol autour du boss, que les joueurs
 * frappent (clic gauche, maintenu ou répété). Comme le cookie de SHCookieClicker, un cœur n'existe qu'en paquets, chez les
 * joueurs proches : un affichage de bloc lumineux et sa zone de frappe. Le serveur ne connaît aucune entité ; le clic arrive en
 * paquet, s'arrête ici et compte un coup. Rien ne peut donc l'atteindre (protections d'île, explosions, pistons) et
 * SuperiorSkyblock2 ne le compte pas dans le niveau de l'île.
 * <p>
 * Au repos, le cœur bat. Chaque coup le fait bondir (il grossit, saute et pivote un instant) puis il retombe un peu plus petit :
 * son usure se voit, sans barre d'action. Au dernier coup il éclate. Le client fait chaque mouvement seul (interpolation).
 * <p>
 * Un cœur est toujours posé sur un bloc solide, avec deux blocs d'air (le sien et celui du dessus) : on peut l'atteindre à pied.
 */
public class HeartManager {

    /** Distance (blocs) jusqu'à laquelle un joueur voit les cœurs. */
    private static final double VIEW_DISTANCE = 48.0;
    private static final int VIEWERS_REFRESH_TICKS = 10;
    /** Distance maximale entre le joueur et le cœur pour qu'un clic compte (le client le vise déjà à portée de main). */
    private static final double REACH = 6.0;
    /** Taille du cœur intact, et part de cette taille perdue juste avant le dernier coup. */
    private static final float REST_SIZE = 0.9f;
    private static final float WORN = 0.3f;
    private static final float TINY = 1.0e-4f;
    /** Battement au repos : toutes les BEAT_TICKS ticks, il gonfle en BEAT_UP ticks et se relâche en BEAT_DOWN. */
    private static final int BEAT_TICKS = 24;
    private static final int BEAT_UP = 3;
    private static final int BEAT_DOWN = 6;
    /** Bond d'un coup : gonfle et saute en POP_UP ticks, retombe en POP_DOWN. */
    private static final int POP_UP = 2;
    private static final int POP_DOWN = 4;
    private static final Vector3d ZERO = new Vector3d(0, 0, 0);

    // Métadonnées (Minecraft 1.20.2 à 26.3)
    private static final int FLAGS = 0;
    private static final int INTERPOLATION_DELAY = 8;
    private static final int TRANSFORMATION_DURATION = 9;
    private static final int TRANSLATION = 11;
    private static final int SCALE = 12;
    private static final int LEFT_ROTATION = 13;
    private static final int GLOW_COLOR = 22;
    private static final int BLOCK_STATE = 23;
    private static final int HITBOX_WIDTH = 8;
    private static final int HITBOX_HEIGHT = 9;
    private static final int HITBOX_RESPONSIVE = 10;

    private static final class Heart {
        private final World world;
        /** Milieu du bas du bloc : là où se tiennent l'affichage et la zone de frappe. */
        private final Location base;
        private final Location center;
        private final HeartOwner owner;
        private final int displayId = SpigotReflectionUtil.generateEntityId();
        private final int hitboxId = SpigotReflectionUtil.generateEntityId();
        private final UUID displayUuid = UUID.randomUUID();
        private final UUID hitboxUuid = UUID.randomUUID();
        private final int hits;
        private final int beatPhase;
        private final Map<UUID, Player> viewers = new HashMap<>();
        private int hitsLeft;
        private boolean broken;
        /** Pose à envoyer plus tard (fin d'un bond, relâchement d'un battement) ; -1 : aucune. */
        private int pendingTick = -1;
        private float pendingSize;
        private int pendingDuration;
        private int removeTick = -1;

        private Heart(World world, Location base, HeartOwner owner, int hits, int beatPhase) {
            this.world = world;
            this.base = base;
            this.center = base.clone().add(0, 0.5, 0);
            this.owner = owner;
            this.hits = hits;
            this.hitsLeft = hits;
            this.beatPhase = beatPhase;
        }

        /** Taille au repos : elle diminue à chaque coup. */
        private float restSize() {
            return REST_SIZE * (1.0f - WORN * (hits - hitsLeft) / (float) hits);
        }
    }

    private final SHEggDragonBoss plugin;
    /** Marqueur des anciens cœurs (entités réelles, jusqu'à la 1.2) : ceux qui restent dans un chunk sont retirés. */
    private final NamespacedKey markerKey;
    /** Cœurs posés, par numéro de leur zone de frappe. */
    private final Map<Integer, Heart> hearts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastHitNanos = new ConcurrentHashMap<>();
    /** Joueurs qui ont déjà reçu l'explication « clic gauche répété » pendant ce combat. */
    private final Set<UUID> hinted = ConcurrentHashMap.newKeySet();
    private final PacketListenerCommon input;
    private final BukkitTask ticker;
    private int blockState = -1;
    private int now;

    public HeartManager(SHEggDragonBoss plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "boss_heart");
        this.input = PacketEvents.getAPI().getEventManager().registerListener(new HeartInput());
        this.ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        // Les mondes sont tous là un tick après l'activation
        Bukkit.getScheduler().runTask(plugin, () -> {
            sweepLegacyBlocks();
            for (World world : Bukkit.getWorlds()) removeStray(world.getEntities());
        });
    }

    // ------------------------------------------------------------------ interrogation

    /** Nombre de cœurs encore debout pour ce boss. */
    public int countAlive(UUID bossId) {
        int alive = 0;
        for (Heart heart : hearts.values()) {
            if (!heart.broken && heart.owner.bossId().equals(bossId)) alive++;
        }
        return alive;
    }

    /** Centres des cœurs encore debout pour ce boss, dans les chunks chargés (repères visuels et boussole). */
    public List<Location> locations(UUID bossId) {
        List<Location> result = new ArrayList<>();
        for (Heart heart : hearts.values()) {
            if (!heart.broken && heart.owner.bossId().equals(bossId)
                && heart.world.isChunkLoaded(heart.base.getBlockX() >> 4, heart.base.getBlockZ() >> 4)) {
                result.add(heart.center.clone());
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ pose

    /**
     * Pose jusqu'à {@code count} cœurs autour de {@code around}, entre {@code min-distance} et {@code max-distance} de lui,
     * espacés d'au moins {@code min-spacing}, à sa hauteur ± {@code vertical-range}. Chaque cœur est posé sur un bloc solide
     * avec deux blocs d'air (le sien et celui du dessus). Si l'île est trop encombrée, une seconde recherche s'élargit
     * (hauteur ± 3 de plus, distance × 1,5). Aucun bloc n'est jamais touché.
     *
     * @return le nombre de cœurs réellement posés
     */
    public int place(HeartOwner owner, Island island, Location around, int count, CreakingSettings.Hearts cfg) {
        World world = around.getWorld();
        if (world == null || count <= 0) return 0;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Block> picked = new ArrayList<>();
        for (int pass = 0; pass < 2 && picked.size() < count; pass++) {
            double maxDistance = pass == 0 ? cfg.maxDistance() : cfg.maxDistance() * 1.5;
            int range = cfg.verticalRange() + pass * 3;
            for (int attempt = 0; attempt < cfg.placementAttempts() && picked.size() < count; attempt++) {
                double angle = random.nextDouble() * Math.PI * 2.0;
                double distance = cfg.minDistance() + random.nextDouble() * Math.max(0.0, maxDistance - cfg.minDistance());
                int x = (int) Math.floor(around.getX() + Math.cos(angle) * distance);
                int z = (int) Math.floor(around.getZ() + Math.sin(angle) * distance);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block candidate = findGrounded(world, x, z, around.getBlockY(), range);
                if (candidate != null && isValidSpot(candidate, island, around, cfg, maxDistance, picked)) picked.add(candidate);
            }
        }
        for (Block block : picked) spawnHeart(owner, block, cfg);
        return picked.size();
    }

    /** Dans la colonne, au plus près de la hauteur du boss : un bloc d'air posé sur un bloc solide, avec de l'air au-dessus. */
    private Block findGrounded(World world, int x, int z, int bossY, int range) {
        for (int offset = 0; offset <= range; offset++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                if (offset == 0 && sign == -1) continue;
                int y = bossY + sign * offset;
                if (y - 1 <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) continue;
                Block block = world.getBlockAt(x, y, z);
                if (block.getType().isAir()
                    && world.getBlockAt(x, y + 1, z).getType().isAir()
                    && world.getBlockAt(x, y - 1, z).getType().isSolid()) {
                    return block;
                }
            }
        }
        return null;
    }

    private boolean isValidSpot(Block block, Island island, Location boss, CreakingSettings.Hearts cfg, double maxDistance, List<Block> alreadyPicked) {
        World world = block.getWorld();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        if (!island.isInside(center) || !world.getWorldBorder().isInside(center)) return false;
        double distance = center.distance(boss);
        if (distance < cfg.minDistance() || distance > maxDistance) return false;
        double minSpacingSquared = cfg.minSpacing() * cfg.minSpacing();
        for (Block other : alreadyPicked) {
            if (other.getLocation().add(0.5, 0.5, 0.5).distanceSquared(center) < minSpacingSquared) return false;
        }
        for (Heart heart : hearts.values()) {
            if (heart.world.equals(world) && heart.center.distanceSquared(center) < minSpacingSquared) return false;
        }
        return true;
    }

    private void spawnHeart(HeartOwner owner, Block block, CreakingSettings.Hearts cfg) {
        Location base = block.getLocation().add(0.5, 0.0, 0.5);
        Heart heart = new Heart(block.getWorld(), base, owner, Math.max(1, cfg.hitsToBreak()), ThreadLocalRandom.current().nextInt(BEAT_TICKS));
        hearts.put(heart.hitboxId, heart);
        refreshViewers(heart);
        cfg.placeFx().spawn(heart.center);
        cfg.placeSound().play(heart.center);
    }

    // ------------------------------------------------------------------ affichage

    /** Un tick : poses en attente, battements, joueurs qui arrivent ou s'éloignent, cœurs brisés à retirer. */
    private void tick() {
        now++;
        if (hearts.isEmpty()) return;
        boolean refresh = now % VIEWERS_REFRESH_TICKS == 0;
        for (Iterator<Heart> it = hearts.values().iterator(); it.hasNext(); ) {
            Heart heart = it.next();
            if (heart.removeTick >= 0 && now >= heart.removeTick) {
                destroyFor(heart, heart.viewers.values());
                it.remove();
                continue;
            }
            if (heart.pendingTick >= 0 && now >= heart.pendingTick) {
                heart.pendingTick = -1;
                sendPose(heart, heart.pendingSize, 0.0f, 0.0f, heart.pendingDuration);
            } else if (!heart.broken && heart.pendingTick < 0 && (now + heart.beatPhase) % BEAT_TICKS == 0) {
                // Battement : il gonfle un peu, puis se relâche
                float rest = heart.restSize();
                sendPose(heart, rest * 1.07f, 0.0f, 0.0f, BEAT_UP);
                schedule(heart, rest, BEAT_UP, BEAT_DOWN);
            }
            if (refresh && !heart.broken) refreshViewers(heart);
        }
    }

    /** Pose à envoyer dans {@code in} ticks (fin d'un bond, relâchement d'un battement). */
    private void schedule(Heart heart, float size, int in, int duration) {
        heart.pendingSize = size;
        heart.pendingDuration = duration;
        heart.pendingTick = now + in;
    }

    /** Les joueurs à moins de VIEW_DISTANCE du cœur le voient ; ceux qui s'éloignent, changent de monde ou partent le perdent. */
    private void refreshViewers(Heart heart) {
        double max = VIEW_DISTANCE * VIEW_DISTANCE;
        Set<UUID> near = new HashSet<>();
        for (Player player : heart.world.getPlayers()) {
            if (player.getLocation().distanceSquared(heart.base) > max) continue;
            near.add(player.getUniqueId());
            if (!heart.viewers.containsKey(player.getUniqueId())) {
                heart.viewers.put(player.getUniqueId(), player);
                spawnFor(heart, player);
            }
        }
        List<Player> gone = new ArrayList<>();
        for (Iterator<Map.Entry<UUID, Player>> it = heart.viewers.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Player> entry = it.next();
            if (!near.contains(entry.getKey())) {
                gone.add(entry.getValue());
                it.remove();
            }
        }
        destroyFor(heart, gone);
    }

    private void spawnFor(Heart heart, Player viewer) {
        CreakingSettings.Hearts cfg = plugin.getConfigManager().creaking().hearts();
        Vector3d position = new Vector3d(heart.base.getX(), heart.base.getY(), heart.base.getZ());
        List<EntityData<?>> display = new ArrayList<>(pose(heart.restSize(), 0.0f, 0.0f, 0));
        display.add(new EntityData<>(BLOCK_STATE, EntityDataTypes.BLOCK_STATE, blockState()));
        if (cfg.glow()) {
            display.add(new EntityData<>(FLAGS, EntityDataTypes.BYTE, (byte) 0x40));
            display.add(new EntityData<>(GLOW_COLOR, EntityDataTypes.INT, cfg.glowColor()));
        }
        List<PacketWrapper<?>> packets = new ArrayList<>(4);
        packets.add(new WrapperPlayServerSpawnEntity(heart.displayId, Optional.of(heart.displayUuid), EntityTypes.BLOCK_DISPLAY,
            position, 0.0f, 0.0f, 0.0f, 0, Optional.of(ZERO)));
        packets.add(new WrapperPlayServerEntityMetadata(heart.displayId, display));
        packets.add(new WrapperPlayServerSpawnEntity(heart.hitboxId, Optional.of(heart.hitboxUuid), EntityTypes.INTERACTION,
            position, 0.0f, 0.0f, 0.0f, 0, Optional.of(ZERO)));
        packets.add(new WrapperPlayServerEntityMetadata(heart.hitboxId, List.of(
            new EntityData<>(HITBOX_WIDTH, EntityDataTypes.FLOAT, 1.0f),
            new EntityData<>(HITBOX_HEIGHT, EntityDataTypes.FLOAT, 1.0f),
            new EntityData<>(HITBOX_RESPONSIVE, EntityDataTypes.BOOLEAN, true))));
        Packets.send(viewer, packets);
    }

    private static void destroyFor(Heart heart, Collection<Player> viewers) {
        for (Player viewer : viewers) {
            Packets.send(viewer, List.of(new WrapperPlayServerDestroyEntities(heart.displayId, heart.hitboxId)));
        }
    }

    /** Envoie à tous ceux qui voient le cœur une pose : taille, saut (blocs), rotation autour de la verticale, durée du trajet. */
    private static void sendPose(Heart heart, float size, float lift, float yaw, int duration) {
        List<EntityData<?>> data = pose(size, lift, yaw, duration);
        for (Player viewer : heart.viewers.values()) {
            Packets.send(viewer, List.of(new WrapperPlayServerEntityMetadata(heart.displayId, data)));
        }
    }

    /** Le bloc affiché a son coin pour origine : la translation garde le milieu du bas du bloc sur l'entité, quelle que soit sa taille. */
    private static List<EntityData<?>> pose(float size, float lift, float yaw, int duration) {
        Quaternionf rotation = new Quaternionf().rotateY((float) Math.toRadians(yaw));
        Vector3f half = rotation.transform(new Vector3f(size / 2.0f, 0.0f, size / 2.0f));
        List<EntityData<?>> data = new ArrayList<>(5);
        data.add(new EntityData<>(INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(TRANSFORMATION_DURATION, EntityDataTypes.INT, duration));
        data.add(new EntityData<>(TRANSLATION, EntityDataTypes.VECTOR3F, new com.github.retrooper.packetevents.util.Vector3f(-half.x, lift, -half.z)));
        data.add(new EntityData<>(SCALE, EntityDataTypes.VECTOR3F, new com.github.retrooper.packetevents.util.Vector3f(size, size, size)));
        data.add(new EntityData<>(LEFT_ROTATION, EntityDataTypes.QUATERNION, new Quaternion4f(rotation.x, rotation.y, rotation.z, rotation.w)));
        return data;
    }

    private int blockState() {
        if (blockState < 0) {
            BlockData data = Material.CREAKING_HEART.createBlockData();
            if (data instanceof CreakingHeart heart) heart.setCreakingHeartState(CreakingHeart.State.AWAKE);
            blockState = SpigotConversionUtil.fromBukkitBlockData(data).getGlobalId();
        }
        return blockState;
    }

    // ------------------------------------------------------------------ coups et destruction

    /** Un clic gauche sur la zone de frappe d'un cœur (fil principal). */
    private void hit(UUID playerId, int hitboxId) {
        Heart heart = hearts.get(hitboxId);
        Player player = Bukkit.getPlayer(playerId);
        if (heart == null || heart.broken || player == null || !player.getWorld().equals(heart.world)) return;
        if (player.getLocation().distanceSquared(heart.center) > REACH * REACH) return;
        CreakingSettings.Hearts cfg = plugin.getConfigManager().creaking().hearts();

        long nanos = System.nanoTime();
        Long last = lastHitNanos.get(playerId);
        if (last != null && nanos - last < (long) (cfg.hitCooldownSeconds() * 1_000_000_000L)) return;
        lastHitNanos.put(playerId, nanos);

        heart.hitsLeft--;
        float yaw = (ThreadLocalRandom.current().nextBoolean() ? 1 : -1) * (8 + ThreadLocalRandom.current().nextFloat() * 10);
        if (heart.hitsLeft > 0) {
            // Bond : il grossit, saute et pivote, puis retombe un peu plus petit
            sendPose(heart, heart.restSize() * 1.28f, 0.14f, yaw, POP_UP);
            schedule(heart, heart.restSize(), POP_UP, POP_DOWN);
            cfg.hitFx().spawn(heart.center);
            cfg.hitSound().play(heart.center);
            if (hinted.add(playerId)) {
                String hint = plugin.getConfigManager().creaking().texts().heartHint();
                if (!hint.isEmpty()) player.sendMessage(hint);
            }
            return;
        }
        // Dernier coup : un grand bond, puis il se réduit à rien et disparaît
        heart.broken = true;
        sendPose(heart, REST_SIZE * 1.4f, 0.22f, yaw * 2, POP_UP);
        schedule(heart, TINY, POP_UP, POP_DOWN);
        heart.removeTick = now + POP_UP + POP_DOWN + 1;
        cfg.breakFx().spawn(heart.center);
        cfg.breakSound().play(heart.center);
        heart.owner.onHeartDestroyed(player);
    }

    public void forgetPlayer(UUID playerId) {
        lastHitNanos.remove(playerId);
        hinted.remove(playerId);
        for (Heart heart : hearts.values()) heart.viewers.remove(playerId);
    }

    // ------------------------------------------------------------------ retrait

    /** Retire tous les cœurs d'un boss (mort, perte de l'entité, arrêt). */
    public void removeAll(UUID bossId) {
        boolean any = false;
        for (Iterator<Heart> it = hearts.values().iterator(); it.hasNext(); ) {
            Heart heart = it.next();
            if (!heart.owner.bossId().equals(bossId)) continue;
            destroyFor(heart, heart.viewers.values());
            it.remove();
            any = true;
        }
        // Fin de combat : le prochain combat réexplique la mécanique à tout le monde
        if (any) hinted.clear();
    }

    /** À l'arrêt du plugin : plus aucun cœur chez personne, plus d'écoute des clics. */
    public void shutdown() {
        for (Heart heart : hearts.values()) destroyFor(heart, heart.viewers.values());
        hearts.clear();
        ticker.cancel();
        PacketEvents.getAPI().getEventManager().unregisterListener(input);
    }

    // ------------------------------------------------------------------ clics (fil réseau)

    /**
     * Clics sur la zone de frappe d'un cœur : elle n'existe que chez le joueur, le serveur ne la connaît pas, le paquet s'arrête
     * ici. Un clic gauche est remis au fil principal ; le clic droit est seulement bloqué.
     */
    private final class HeartInput extends PacketListenerAbstract {
        private HeartInput() {
            super(PacketListenerPriority.NORMAL);
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            int entityId;
            boolean left;
            if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
                // Depuis 26.1, le clic gauche sur une entité a son propre paquet
                entityId = new WrapperPlayClientAttack(event).getEntityId();
                left = true;
            } else if (event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY) {
                WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
                entityId = packet.getEntityId();
                left = packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
            } else {
                return;
            }
            if (!hearts.containsKey(entityId) || event.getUser() == null || event.getUser().getUUID() == null) return;
            event.setCancelled(true);
            if (!left) return;
            UUID playerId = event.getUser().getUUID();
            Bukkit.getScheduler().runTask(plugin, () -> hit(playerId, entityId));
        }
    }

    // ------------------------------------------------------------------ anciens cœurs (entités réelles, jusqu'à la 1.2)

    /** Entités de cœur d'avant la 1.3 laissées dans un chunk : retirées un tick après son chargement. */
    public void onEntitiesLoad(Collection<? extends Entity> entities) {
        List<Entity> marked = entities.stream().filter(this::isMarked).map(e -> (Entity) e).toList();
        if (marked.isEmpty()) return;
        Bukkit.getScheduler().runTask(plugin, () -> removeStray(marked));
    }

    private boolean isMarked(Entity entity) {
        return entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    private void removeStray(Collection<? extends Entity> entities) {
        int removed = 0;
        for (Entity entity : entities) {
            if (isMarked(entity)) {
                entity.remove();
                removed++;
            }
        }
        if (removed > 0) plugin.getLogger().info(removed + " ancienne(s) entité(s) de cœur du Creaking retirée(s).");
    }

    /** Anciennes versions : les cœurs étaient de vrais blocs listés dans creaking-hearts.yml. Ceux qui restent sont retirés. */
    private void sweepLegacyBlocks() {
        File file = new File(plugin.getDataFolder(), "creaking-hearts.yml");
        if (!file.exists()) return;
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        int removed = 0;
        for (String raw : config.getStringList("hearts")) {
            try {
                String[] parts = raw.split(";");
                World world = Bukkit.getWorld(parts[0]);
                int x = Integer.parseInt(parts[1]);
                int z = Integer.parseInt(parts[3]);
                if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block block = world.getBlockAt(x, Integer.parseInt(parts[2]), z);
                if (block.getType() == Material.CREAKING_HEART) {
                    block.setType(Material.AIR, false);
                    removed++;
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Cœur illisible dans creaking-hearts.yml : '" + raw + "'.");
            }
        }
        if (removed > 0) plugin.getLogger().info(removed + " ancien(s) cœur(s) du Creaking (blocs) retiré(s).");
        if (!file.delete()) plugin.getLogger().warning("Impossible de supprimer creaking-hearts.yml (inutilisé depuis les cœurs en affichage).");
    }
}

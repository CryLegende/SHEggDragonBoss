package org.Cry_Legende.sHEggDragonBoss.model;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modèle 3D d'un boss, fait de têtes de joueur envoyées par paquets : aucune entité côté serveur, rien pour les joueurs
 * qui ne voient pas le boss. Comme les compagnons de SHCompagnons :
 * <ul>
 *   <li>la première pièce est montée sur le boss (passager côté client), les autres sur elle : le client les déplace
 *       avec le boss, aucun paquet de position ;</li>
 *   <li>quand le corps du boss tourne, une rotation par pièce, lissée par le client ;</li>
 *   <li>les animations sont des images clés : chaque pièce reçoit sa prochaine pose et la durée pour l'atteindre, le
 *       client fait le trajet seul. Une pièce immobile dans un état ne coûte rien.</li>
 * </ul>
 * L'état de fond (idle, walk...) est choisi par le boss à chaque tick ; une action (attaque, rugissement...) le remplace
 * le temps de sa durée. Chaque changement d'état amène les pièces en {@link #BLEND_TICKS} ticks à leur nouvelle pose.
 * Les méthodes publiques sont appelées sur le fil principal ; le suivi des joueurs peut venir d'un fil de monde.
 */
public final class BossModel {

    /** Fondu d'un état à l'autre (ticks). */
    private static final int BLEND_TICKS = 3;
    /** Orientation renvoyée quand le corps a tourné d'au moins ce nombre de degrés, au plus tous les TURN_INTERVAL ticks. */
    private static final float TURN_THRESHOLD = 4.0f;
    private static final int TURN_INTERVAL = 3;
    /** Lissage de l'orientation par le client (ticks). */
    private static final int TURN_SMOOTHING = 3;
    /** Premier affichage : le modèle reste réduit à un point le temps que le client télécharge les textures des têtes. */
    private static final int PRELOAD_TICKS = 10;
    private static final int REVEAL_TICKS = 3;
    private static final float TINY = 1.0e-4f;
    /** Disparition au coup de grâce (ticks). */
    private static final int VANISH_TICKS = 14;
    private static final Vector3d ZERO = new Vector3d(0, 0, 0);

    private final LivingEntity entity;
    private final ModelFile model;
    private final float scale;
    private final Quaternionfc facing;
    private final int[] ids;
    private final UUID[] uuids;

    // Joueurs qui ont le modèle chez eux ; ceux à qui l'envoyer au prochain tick (après le paquet d'apparition du boss) ;
    // ceux chez qui il est encore réduit à un point (tick de l'apparition)
    private final Map<UUID, Player> shown = new ConcurrentHashMap<>();
    private final Map<UUID, Player> pending = new LinkedHashMap<>();
    private final Map<UUID, Integer> preloading = new LinkedHashMap<>();

    // Dernière pose visée par chaque pièce (repère du modèle) et tick de sa prochaine image clé
    private final Affine[] poses;
    private final int[] poseDue;

    private ModelFile.State loop;
    private ModelFile.State state;
    private int stateStart;
    private boolean hold;
    private ModelFile.State queued;
    private int queuedAt;
    private int queuedOffset;
    private float sentYaw;
    private int nextTurnTick;
    private int now;
    private boolean vanished;
    private boolean destroyed;

    /**
     * @param scale  taille du modèle (1 = celle du fichier)
     * @param facing rotation en plus autour de la verticale (degrés), 0 : l'avant du modèle est celui du boss
     */
    public BossModel(LivingEntity entity, ModelFile model, float scale, float facing) {
        this.entity = entity;
        this.model = model;
        this.scale = scale;
        // Avant du modèle = avant du boss tel quel (vérifié en jeu) ; facing ne sert qu'à corriger un autre modèle
        this.facing = new Quaternionf().rotateY((float) Math.toRadians(facing));
        int count = model.parts().size();
        this.ids = new int[count];
        this.uuids = new UUID[count];
        this.poses = new Affine[count];
        this.poseDue = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = Packets.newEntityId();
            uuids[i] = UUID.randomUUID();
        }
        ModelFile.State idle = model.state("idle");
        this.loop = idle != null ? idle : model.states().values().iterator().next();
        this.state = loop;
        for (int i = 0; i < count; i++) {
            Part part = model.parts().get(i);
            Track track = part.stateTrack(state.index());
            poses[i] = track != null ? track.at(0) : part.transform();
        }
        this.sentYaw = entity.getBodyYaw();
    }

    public LivingEntity entity() {
        return entity;
    }

    /** Durée d'un état du modèle (ticks), 0 s'il n'existe pas. */
    public int length(String name) {
        ModelFile.State s = model.state(name);
        return s == null ? 0 : s.length();
    }

    // ------------------------------------------------------------------ joueurs qui le voient

    /** Premier affichage : tous ceux qui voient déjà le boss. */
    public synchronized void start() {
        for (Player viewer : entity.getTrackedBy()) {
            pending.put(viewer.getUniqueId(), viewer);
        }
    }

    /** Ce joueur commence à voir le boss : le modèle suivra au prochain tick, après le paquet d'apparition du boss. */
    public synchronized void track(Player viewer) {
        if (!destroyed && !vanished && !shown.containsKey(viewer.getUniqueId())) {
            pending.put(viewer.getUniqueId(), viewer);
        }
    }

    /** Ce joueur ne voit plus le boss : le modèle disparaît aussitôt de chez lui. */
    public synchronized void untrack(Player viewer) {
        pending.remove(viewer.getUniqueId());
        preloading.remove(viewer.getUniqueId());
        if (shown.remove(viewer.getUniqueId()) != null) {
            Packets.send(viewer, List.of(new WrapperPlayServerDestroyEntities(ids)));
        }
    }

    /** Joueur déconnecté : rien à lui envoyer. */
    public synchronized void forget(UUID viewer) {
        pending.remove(viewer);
        preloading.remove(viewer);
        shown.remove(viewer);
    }

    /** Retire le modèle de chez tout le monde (mort terminée, boss perdu, arrêt du plugin). */
    public synchronized void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        int[] riders = realPassengers();
        for (Player viewer : shown.values()) {
            List<PacketWrapper<?>> packets = new ArrayList<>(2);
            packets.add(new WrapperPlayServerDestroyEntities(ids));
            if (entity.isValid()) {
                packets.add(new WrapperPlayServerSetPassengers(entity.getEntityId(), riders));
            }
            Packets.send(viewer, packets);
        }
        shown.clear();
        pending.clear();
        preloading.clear();
    }

    public boolean destroyed() {
        return destroyed;
    }

    // ------------------------------------------------------------------ états

    /** État de fond voulu (idle, walk, fige, vol) : joué tout de suite si aucune action n'est en cours. */
    public synchronized void loop(String name) {
        ModelFile.State wanted = model.state(name);
        if (wanted == null || wanted == loop) {
            return;
        }
        loop = wanted;
        if (!busy()) {
            switchState(wanted, 0, false);
        }
    }

    /**
     * Joue une action une fois, à la place de l'état en cours.
     *
     * @param delay  ticks d'attente avant de la lancer (le modèle garde son état d'ici là)
     * @param offset instant de l'action où commencer (ticks) : pour caler un impact sur un coup déjà porté
     * @param holdEnd vrai : la pose finale est tenue (mort) au lieu de revenir à l'état de fond
     */
    public synchronized void play(String name, int delay, int offset, boolean holdEnd) {
        ModelFile.State action = model.state(name);
        if (action == null || destroyed || vanished || hold) {
            return;
        }
        if (delay > 0) {
            queued = action;
            queuedAt = now + delay;
            queuedOffset = offset;
            return;
        }
        queued = null;
        switchState(action, Math.max(0, Math.min(offset, action.length() - 1)), holdEnd);
    }

    public void play(String name) {
        play(name, 0, 0, false);
    }

    /** Une action est en cours (ou va partir) : l'état de fond attend. */
    public synchronized boolean busy() {
        return queued != null || hold || (state.once() && now - stateStart < state.length());
    }

    /** Cette action est-elle en cours ? */
    public synchronized boolean playing(String name) {
        return busy() && (state.name().equals(name) || (queued != null && queued.name().equals(name)));
    }

    /** Coup de grâce : chaque pièce rétrécit jusqu'à disparaître, plus rien n'est envoyé ensuite. */
    public synchronized void vanish() {
        if (vanished || destroyed) {
            return;
        }
        vanished = true;
        pending.clear();
        List<List<EntityData<?>>> updates = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            Affine affine = placed(poses[i]);
            List<EntityData<?>> data = new ArrayList<>(3);
            data.add(new EntityData<>(Part.INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
            data.add(new EntityData<>(Part.TRANSFORMATION_DURATION, EntityDataTypes.INT, VANISH_TICKS));
            data.add(new EntityData<>(Part.SCALE, EntityDataTypes.VECTOR3F, vector(new Vector3f(affine.scale()).mul(TINY))));
            updates.add(data);
        }
        sendUpdates(ids, updates);
    }

    // ------------------------------------------------------------------ tick

    /** Un tick du serveur (fil principal). */
    public synchronized void tick() {
        now++;
        if (destroyed || vanished) {
            return;
        }
        if (!pending.isEmpty()) {
            spawnPending();
        }
        if (!preloading.isEmpty()) {
            reveal();
        }
        turn();
        if (queued != null && now >= queuedAt) {
            ModelFile.State next = queued;
            queued = null;
            switchState(next, Math.max(0, Math.min(queuedOffset, next.length() - 1)), false);
        }
        if (state.once() && !hold && now - stateStart >= state.length()) {
            switchState(loop, 0, false);
        }
        animate();
    }

    /**
     * Change d'état : chaque pièce rejoint en BLEND_TICKS la pose que le nouvel état lui donne à cet instant-là, puis
     * suit ses images clés. offset : instant du nouvel état où il commence.
     */
    private void switchState(ModelFile.State next, int offset, boolean holdEnd) {
        state = next;
        hold = holdEnd;
        stateStart = now - offset;
        int until = now + BLEND_TICKS;
        List<List<EntityData<?>>> updates = new ArrayList<>(ids.length);
        int[] updated = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            Part part = model.parts().get(i);
            Track track = part.stateTrack(next.index());
            Affine pose;
            if (track == null) {
                pose = part.transform();
            } else if (next.once()) {
                int at = Math.min(offset + BLEND_TICKS, next.length() - 1);
                pose = at >= track.lastTick() ? track.lastPose() : track.at(at);
            } else {
                pose = track.at(Math.floorMod(offset + BLEND_TICKS, track.loop()));
            }
            poseDue[i] = until;
            if (pose.equals(poses[i])) {
                continue;
            }
            List<EntityData<?>> data = pose(pose, poses[i], BLEND_TICKS);
            poses[i] = pose;
            updated[updates.size()] = ids[i];
            updates.add(data);
        }
        sendUpdates(updated, updates);
    }

    /** Envoie à chaque pièce dont l'image clé est due la suivante, avec le temps pour l'atteindre. */
    private void animate() {
        List<List<EntityData<?>>> updates = null;
        int[] updated = null;
        int local = now - stateStart;
        for (int i = 0; i < ids.length; i++) {
            if (now < poseDue[i]) {
                continue;
            }
            Track track = model.parts().get(i).stateTrack(state.index());
            if (track == null || track.keys() == 1) {
                // La pièce garde une seule pose dans cet état : elle l'a reçue au changement d'état
                poseDue[i] = Integer.MAX_VALUE;
                continue;
            }
            Affine target;
            int duration;
            if (state.once()) {
                if (local >= state.length()) {
                    poseDue[i] = Integer.MAX_VALUE;
                    continue;
                }
                int next = track.nextTick(local);
                if (next >= track.loop()) {
                    // Après la dernière image clé, la pièce la tient jusqu'à la fin de l'action (puis fondu vers l'état
                    // de fond, ou pose finale tenue pour la mort) : jamais de retour vers la première pose, qui peut être
                    // tout autre (l'apparition commence sous terre)
                    poseDue[i] = Integer.MAX_VALUE;
                    continue;
                }
                target = track.at(next);
                duration = next - local;
            } else {
                int at = Math.floorMod(local, track.loop());
                int next = track.nextTick(at);
                target = track.at(next % track.loop());
                duration = next - at;
            }
            poseDue[i] = now + duration;
            if (target.equals(poses[i])) {
                continue;
            }
            if (updates == null) {
                updates = new ArrayList<>(ids.length);
                updated = new int[ids.length];
            }
            updated[updates.size()] = ids[i];
            updates.add(pose(target, poses[i], duration));
            poses[i] = target;
        }
        if (updates != null) {
            sendUpdates(updated, updates);
        }
    }

    /** Métadonnées d'une pièce qui part vers « target » en « duration » ticks : seulement ce qui change depuis « from ». */
    private List<EntityData<?>> pose(Affine target, Affine from, int duration) {
        Affine affine = placed(target);
        List<EntityData<?>> data = new ArrayList<>(5);
        data.add(new EntityData<>(Part.INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
        data.add(new EntityData<>(Part.TRANSFORMATION_DURATION, EntityDataTypes.INT, Math.max(1, duration)));
        if (!target.translation().equals(from.translation())) {
            data.add(new EntityData<>(Part.TRANSLATION, EntityDataTypes.VECTOR3F, vector(affine.translation())));
        }
        if (!target.left().equals(from.left())) {
            data.add(new EntityData<>(Part.LEFT_ROTATION, EntityDataTypes.QUATERNION, quaternion(affine.left())));
        }
        if (!target.scale().equals(from.scale())) {
            data.add(new EntityData<>(Part.SCALE, EntityDataTypes.VECTOR3F, vector(affine.scale())));
        }
        return data;
    }

    /** Orientation : renvoyée quand le corps du boss a assez tourné, lissée par le client ; figée une fois mort. */
    private void turn() {
        float yaw = entity.getBodyYaw();
        if (shown.isEmpty()) {
            sentYaw = yaw;
            return;
        }
        if (hold || now < nextTurnTick || Math.abs(Location.normalizeYaw(yaw - sentYaw)) < TURN_THRESHOLD) {
            return;
        }
        sentYaw = yaw;
        nextTurnTick = now + TURN_INTERVAL;
        for (Player viewer : shown.values()) {
            List<PacketWrapper<?>> packets = new ArrayList<>(ids.length);
            for (int id : ids) {
                packets.add(new WrapperPlayServerEntityRotation(id, yaw, 0.0f, false));
            }
            Packets.send(viewer, packets);
        }
    }

    // ------------------------------------------------------------------ apparition chez un joueur

    private void spawnPending() {
        Location location = entity.getLocation();
        Vector3d position = new Vector3d(location.getX(), location.getY() + entity.getHeight(), location.getZ());
        int[] riders = new int[ids.length - 1];
        System.arraycopy(ids, 1, riders, 0, riders.length);
        int[] real = realPassengers();
        int[] onBoss = new int[real.length + 1];
        System.arraycopy(real, 0, onBoss, 0, real.length);
        onBoss[real.length] = ids[0];
        for (Player viewer : pending.values()) {
            if (!viewer.isOnline()) {
                continue;
            }
            List<PacketWrapper<?>> packets = new ArrayList<>(ids.length * 2 + 4);
            packets.add(new WrapperPlayServerBundle());
            for (int i = 0; i < ids.length; i++) {
                packets.add(new WrapperPlayServerSpawnEntity(ids[i], Optional.of(uuids[i]), model.parts().get(i).type(),
                    position, 0.0f, sentYaw, 0.0f, 0, Optional.of(ZERO)));
                packets.add(new WrapperPlayServerEntityMetadata(ids[i], fullData(i, true)));
            }
            if (riders.length > 0) {
                packets.add(new WrapperPlayServerSetPassengers(ids[0], riders));
            }
            packets.add(new WrapperPlayServerSetPassengers(entity.getEntityId(), onBoss));
            packets.add(new WrapperPlayServerBundle());
            // Rangé avant la remise du lot : le fil réseau peut le traiter aussitôt
            shown.put(viewer.getUniqueId(), viewer);
            preloading.put(viewer.getUniqueId(), now);
            Packets.send(viewer, packets);
        }
        pending.clear();
    }

    /** Fin du préchargement : le modèle se déploie, dans sa pose du moment. */
    private void reveal() {
        for (Iterator<Map.Entry<UUID, Integer>> it = preloading.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Integer> entry = it.next();
            if (now - entry.getValue() < PRELOAD_TICKS) {
                continue;
            }
            it.remove();
            Player viewer = shown.get(entry.getKey());
            if (viewer == null) {
                continue;
            }
            List<PacketWrapper<?>> packets = new ArrayList<>(ids.length);
            for (int i = 0; i < ids.length; i++) {
                Affine affine = placed(poses[i]);
                List<EntityData<?>> data = new ArrayList<>(5);
                data.add(new EntityData<>(Part.INTERPOLATION_DELAY, EntityDataTypes.INT, 0));
                data.add(new EntityData<>(Part.TRANSFORMATION_DURATION, EntityDataTypes.INT, REVEAL_TICKS));
                data.add(new EntityData<>(Part.TRANSLATION, EntityDataTypes.VECTOR3F, vector(affine.translation())));
                data.add(new EntityData<>(Part.LEFT_ROTATION, EntityDataTypes.QUATERNION, quaternion(affine.left())));
                data.add(new EntityData<>(Part.SCALE, EntityDataTypes.VECTOR3F, vector(affine.scale())));
                packets.add(new WrapperPlayServerEntityMetadata(ids[i], data));
            }
            Packets.send(viewer, packets);
        }
    }

    /** Toutes les métadonnées d'une pièce, pour son apparition ; « tiny » : réduite à un point (préchargement). */
    private List<EntityData<?>> fullData(int index, boolean tiny) {
        Part part = model.parts().get(index);
        List<EntityData<?>> data = new ArrayList<>(part.staticData().size() + 6);
        data.addAll(part.staticData());
        data.add(new EntityData<>(Part.POS_ROT_DURATION, EntityDataTypes.INT, TURN_SMOOTHING));
        Affine affine = placed(poses[index]);
        data.add(new EntityData<>(Part.TRANSLATION, EntityDataTypes.VECTOR3F, vector(affine.translation())));
        data.add(new EntityData<>(Part.LEFT_ROTATION, EntityDataTypes.QUATERNION, quaternion(affine.left())));
        data.add(new EntityData<>(Part.SCALE, EntityDataTypes.VECTOR3F,
            vector(tiny ? new Vector3f(affine.scale()).mul(TINY) : affine.scale())));
        data.add(new EntityData<>(Part.RIGHT_ROTATION, EntityDataTypes.QUATERNION, quaternion(affine.right())));
        return data;
    }

    /**
     * Transformation d'une pièce chez le client : sa pose dans le modèle, à la taille voulue, tournée pour faire face à
     * l'avant du boss, et descendue de la hauteur du boss (le modèle est monté sur son point de passager, au-dessus de
     * sa tête : ses pieds reviennent ainsi à ceux du boss).
     */
    private Affine placed(Affine pose) {
        return pose.placed(new Vector3f(0, (float) -entity.getHeight(), 0), facing, scale);
    }

    /** Vrais passagers du boss, gardés dans la liste envoyée. */
    private int[] realPassengers() {
        List<Entity> passengers = entity.getPassengers();
        int[] out = new int[passengers.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = passengers.get(i).getEntityId();
        }
        return out;
    }

    private void sendUpdates(int[] entities, List<List<EntityData<?>>> updates) {
        if (updates.isEmpty()) {
            return;
        }
        for (Player viewer : shown.values()) {
            // Encore réduit à un point chez ce joueur : il recevra la pose du moment en se déployant
            if (preloading.containsKey(viewer.getUniqueId())) {
                continue;
            }
            List<PacketWrapper<?>> packets = new ArrayList<>(updates.size());
            for (int n = 0; n < updates.size(); n++) {
                packets.add(new WrapperPlayServerEntityMetadata(entities[n], updates.get(n)));
            }
            Packets.send(viewer, packets);
        }
    }

    private static com.github.retrooper.packetevents.util.Vector3f vector(Vector3fc v) {
        return new com.github.retrooper.packetevents.util.Vector3f(v.x(), v.y(), v.z());
    }

    private static Quaternion4f quaternion(Quaternionfc q) {
        return new Quaternion4f(q.x(), q.y(), q.z(), q.w());
    }
}

package org.Cry_Legende.sHEggDragonBoss.model;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.util.Quaternion4f;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBundle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityRotation;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import net.kyori.adventure.text.Component;
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
 *   <li>quand le boss tourne, une rotation par pièce, lissée par le client ;</li>
 *   <li>les animations sont des images clés : chaque pièce reçoit sa prochaine pose et la durée pour l'atteindre, le
 *       client fait le trajet seul. Une pièce immobile dans un état ne coûte rien.</li>
 * </ul>
 * L'état de fond (idle, walk, vol...) est choisi par le boss à chaque tick ; une action (attaque, rugissement...) le
 * remplace le temps de sa durée. Une action « en calque » ({@link #playOver}) ne prend que les pièces qu'elle anime (la
 * tête et le cou d'un dragon qui crache) : les autres continuent l'état de fond sans à-coup. Chaque changement d'état
 * amène les pièces en {@link #BLEND_TICKS} ticks à leur nouvelle pose.
 * Les méthodes publiques sont appelées sur le fil principal ; le suivi des joueurs peut venir d'un fil de monde.
 */
public final class BossModel {

    /**
     * Réglages de l'affichage.
     *
     * @param originHeight    hauteur de l'origine du modèle sur l'entité, en part de sa hauteur (0 : pieds du modèle aux
     *                        pieds du boss, le Creaking ; 0,5 : centre du modèle au centre de la hitbox, le dragon)
     * @param yawFromLocation vrai : l'orientation est celle de la position du boss (un boss déplacé à la main) ; faux :
     *                        celle de son corps (IA vanilla)
     * @param turnTicks       lissage des rotations par le client (ticks)
     * @param predictTurn     vrai : chaque rotation envoyée vise où le boss regardera à la fin du lissage, au rythme où
     *                        il tourne ; un boss qui tourne en rond régulier n'a besoin que d'une rotation par lissage
     */
    public record Options(float originHeight, boolean yawFromLocation, int turnTicks, boolean predictTurn) {
        /** Le Creaking : pieds au sol, orientation du corps, lissage court, rotations au fil de l'eau. */
        public static final Options GROUND = new Options(0.0f, false, 3, false);
    }

    /** Fondu d'un état à l'autre (ticks). */
    private static final int BLEND_TICKS = 3;
    /** Orientation renvoyée quand le corps a tourné d'au moins ce nombre de degrés, au plus tous les TURN_INTERVAL ticks. */
    private static final float TURN_THRESHOLD = 4.0f;
    private static final int TURN_INTERVAL = 3;
    /**
     * Rotation prédite : au moins PREDICT_GAP ticks entre deux envois, un écart de PREDICT_THRESHOLD degrés avec ce que le
     * client montre pour en déclencher un, au-delà de PREDICT_JUMP degrés d'un tick à l'autre ce n'est pas une vitesse ;
     * l'orientation suivie est lissée (PREDICT_SMOOTHING par tick) : le cap d'un boss déplacé à la main tremble de quelques
     * degrés d'un tick à l'autre, et chaque tremblement coûterait une rotation par pièce. 7 ticks entre deux envois, sous
     * les 10 ticks d'interpolation du client (turn-ticks) : le virage reste continu, avec au plus 3 envois par seconde.
     */
    private static final int PREDICT_GAP = 7;
    private static final float PREDICT_THRESHOLD = 8.0f;
    private static final float PREDICT_JUMP = 45.0f;
    private static final float PREDICT_SMOOTHING = 0.35f;
    /** Premier affichage : le modèle reste réduit à un point le temps que le client télécharge les textures des têtes. */
    private static final int PRELOAD_TICKS = 10;
    private static final int REVEAL_TICKS = 3;
    private static final float TINY = 1.0e-4f;
    /** Disparition au coup de grâce (ticks). */
    private static final int VANISH_TICKS = 14;
    private static final Vector3d ZERO = new Vector3d(0, 0, 0);
    /** Nom du boss : au-dessus de la tête du modèle (blocs). Le client ne montre pas le nom d'une entité qui porte des passagers. */
    private static final float LABEL_GAP = 0.35f;
    private static final byte BILLBOARD_CENTER = 3;

    private final LivingEntity entity;
    private final ModelFile model;
    private final float scale;
    private final Quaternionfc facing;
    private final Options options;
    private final int[] ids;
    private final UUID[] uuids;
    private final int labelId;
    private final UUID labelUuid;
    /** Hauteur du haut du modèle (blocs, à l'échelle 1, depuis son origine). */
    private final float top;
    private Component label;

    // Joueurs qui ont le modèle chez eux ; ceux à qui l'envoyer au prochain tick (après le paquet d'apparition du boss) ;
    // ceux chez qui il est encore réduit à un point (tick de l'apparition)
    private final Map<UUID, Player> shown = new ConcurrentHashMap<>();
    private final Map<UUID, Player> pending = new LinkedHashMap<>();
    private final Map<UUID, Integer> preloading = new LinkedHashMap<>();

    // Dernière pose visée par chaque pièce (repère du modèle) et tick de sa prochaine image clé
    private final Affine[] poses;
    private final int[] poseDue;
    // État suivi par chaque pièce et tick où elle l'a commencé : toutes le même, sauf pendant une action en calque
    private final ModelFile.State[] partState;
    private final int[] partStart;

    private ModelFile.State loop;
    /** Tick de départ de l'état de fond : sa phase, reprise après une action en calque. */
    private int loopStart;
    private ModelFile.State state;
    private int stateStart;
    private boolean hold;
    private boolean layered;
    private ModelFile.State queued;
    private int queuedAt;
    private int queuedOffset;
    private boolean queuedLayered;
    private float sentYaw;
    private int nextTurnTick;
    // Rotation prédite : ce que le client affiche (de shownFrom à shownTo, de shownAt à shownAt + turnTicks)
    private float lastYaw;
    private float smoothYaw;
    private boolean haveLastYaw;
    private float yawRate;
    private float shownFrom;
    private float shownTo;
    private int shownAt;
    private int now;
    private boolean vanished;
    private boolean destroyed;
    // Dernière position du boss, lue par le filtre de paquets (fil réseau)
    private volatile double lastX;
    private volatile double lastY;
    private volatile double lastZ;

    public BossModel(LivingEntity entity, ModelFile model, float scale, float facing) {
        this(entity, model, scale, facing, Options.GROUND);
    }

    /**
     * @param scale  taille du modèle (1 = celle du fichier)
     * @param facing rotation en plus autour de la verticale (degrés), 0 : l'avant du modèle est celui du boss
     */
    public BossModel(LivingEntity entity, ModelFile model, float scale, float facing, Options options) {
        this.entity = entity;
        this.model = model;
        this.scale = scale;
        this.options = options;
        // Avant du modèle = avant du boss tel quel (vérifié en jeu) ; facing ne sert qu'à corriger un autre modèle
        this.facing = new Quaternionf().rotateY((float) Math.toRadians(facing));
        int count = model.parts().size();
        this.ids = new int[count];
        this.uuids = new UUID[count];
        this.poses = new Affine[count];
        this.poseDue = new int[count];
        this.partState = new ModelFile.State[count];
        this.partStart = new int[count];
        for (int i = 0; i < count; i++) {
            ids[i] = Packets.newEntityId();
            uuids[i] = UUID.randomUUID();
        }
        this.labelId = Packets.newEntityId();
        this.labelUuid = UUID.randomUUID();
        float highest = 0;
        for (Part part : model.parts()) {
            highest = Math.max(highest, part.transform().translation().y());
        }
        this.top = highest;
        ModelFile.State idle = model.state("idle");
        if (idle == null) {
            idle = model.state("vol");
        }
        this.loop = idle != null ? idle : model.states().values().iterator().next();
        this.state = loop;
        for (int i = 0; i < count; i++) {
            Part part = model.parts().get(i);
            Track track = part.stateTrack(state.index());
            poses[i] = track != null ? track.at(0) : part.transform();
            partState[i] = loop;
        }
        this.sentYaw = yaw();
        this.shownFrom = sentYaw;
        this.shownTo = sentYaw;
        this.shownAt = -options.turnTicks();
    }

    public LivingEntity entity() {
        return entity;
    }

    /** Ce joueur a-t-il le modèle chez lui ? (fil réseau) */
    public boolean shownTo(UUID viewer) {
        return shown.containsKey(viewer);
    }

    /** Le boss est-il à moins de {@code radius} blocs de ce point ? (fil réseau, dernière position connue) */
    public boolean near(double x, double y, double z, double radius) {
        double dx = x - lastX, dy = y - lastY, dz = z - lastZ;
        return dx * dx + dy * dy + dz * dz <= radius * radius;
    }

    /** Durée d'un état du modèle (ticks), 0 s'il n'existe pas. */
    public int length(String name) {
        ModelFile.State s = model.state(name);
        return s == null ? 0 : s.length();
    }

    /** Taille du modèle (1 = celle du fichier). */
    public float scale() {
        return scale;
    }

    /**
     * Instant (ticks) de l'état de fond {@code name} quand il se voit (seul ou sous une action en calque), -1 sinon :
     * de quoi caler sur l'animation un mouvement fait par le boss lui-même (le dragon monte à chaque coup d'aile).
     */
    public synchronized int loopTick(String name) {
        if (!loop.name().equals(name) || vanished || destroyed || (state != loop && !layered)) {
            return -1;
        }
        return Math.floorMod(now - loopStart, Math.max(1, loop.length()));
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
            Packets.send(viewer, List.of(new WrapperPlayServerDestroyEntities(allIds())));
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
            packets.add(new WrapperPlayServerDestroyEntities(allIds()));
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

    /** Nom affiché au-dessus du modèle (null : caché, pendant le Noir par exemple). */
    public synchronized void label(Component text) {
        Component wanted = text == null ? Component.empty() : text;
        if (wanted.equals(label)) {
            return;
        }
        label = wanted;
        for (Player viewer : shown.values()) {
            Packets.send(viewer, List.of(new WrapperPlayServerEntityMetadata(labelId, List.of(new EntityData<>(Part.TEXT, EntityDataTypes.ADV_COMPONENT, wanted)))));
        }
    }

    // ------------------------------------------------------------------ états

    /**
     * État de fond voulu (idle, walk, fige, vol...) : joué tout de suite si aucune action n'est en cours. Une action en
     * calque s'arrête pour lui laisser la place (le dragon qui plonge n'attend pas la fin de sa morsure).
     */
    public synchronized void loop(String name) {
        ModelFile.State wanted = model.state(name);
        if (wanted == null || wanted == loop) {
            return;
        }
        loop = wanted;
        if (layered && !hold) {
            queued = null;
            switchState(wanted, 0, false, false, false);
        } else if (!busy()) {
            switchState(wanted, 0, false, false, false);
        }
    }

    /**
     * Joue une action une fois, à la place de l'état en cours.
     *
     * @param delay   ticks d'attente avant de la lancer (le modèle garde son état d'ici là)
     * @param offset  instant de l'action où commencer (ticks) : pour caler un impact sur un coup déjà porté
     * @param holdEnd vrai : la pose finale est tenue (mort) au lieu de revenir à l'état de fond
     */
    public synchronized void play(String name, int delay, int offset, boolean holdEnd) {
        start(name, delay, offset, holdEnd, false);
    }

    public void play(String name) {
        play(name, 0, 0, false);
    }

    /**
     * Joue une action en calque : seules les pièces qu'elle anime la suivent, les autres continuent l'état de fond ; à
     * la fin, l'état de fond reprend à sa phase (pas depuis son début). Sans effet pendant une action entière.
     */
    public synchronized void playOver(String name, int delay, int offset) {
        if (busy() && !layered) {
            return;
        }
        start(name, delay, offset, false, true);
    }

    public void playOver(String name) {
        playOver(name, 0, 0);
    }

    private void start(String name, int delay, int offset, boolean holdEnd, boolean overlay) {
        ModelFile.State action = model.state(name);
        if (action == null || destroyed || vanished || hold) {
            return;
        }
        if (delay > 0) {
            queued = action;
            queuedAt = now + delay;
            queuedOffset = offset;
            queuedLayered = overlay;
            return;
        }
        queued = null;
        if (layered && !overlay) {
            // Une action entière remplace un calque en cours : toutes les pièces la suivent
            layered = false;
        }
        switchState(action, Math.max(0, Math.min(offset, action.length() - 1)), holdEnd, overlay, false);
    }

    /** Interrompt l'action en cours (hors pose finale tenue) : l'état de fond reprend tout de suite. */
    public synchronized void cut() {
        queued = null;
        if (hold || state == loop) {
            return;
        }
        if (layered) {
            switchState(loop, Math.floorMod(now - loopStart, Math.max(1, loop.length())), false, false, true);
        } else {
            switchState(loop, 0, false, false, false);
        }
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
        label(null);
    }

    // ------------------------------------------------------------------ tick

    /** Un tick du serveur (fil principal). */
    public synchronized void tick() {
        now++;
        Location here = entity.getLocation();
        lastX = here.getX();
        lastY = here.getY();
        lastZ = here.getZ();
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
            boolean overlay = queuedLayered;
            queued = null;
            if (layered && !overlay) {
                layered = false;
            }
            switchState(next, Math.max(0, Math.min(queuedOffset, next.length() - 1)), false, overlay, false);
        }
        if (state.once() && !hold && now - stateStart >= state.length()) {
            if (layered) {
                // Fin d'un calque : les pièces qu'il avait prises rejoignent l'état de fond à sa phase
                switchState(loop, Math.floorMod(now - loopStart, Math.max(1, loop.length())), false, false, true);
            } else {
                switchState(loop, 0, false, false, false);
            }
        }
        animate();
    }

    /**
     * Change d'état : chaque pièce concernée rejoint en BLEND_TICKS la pose que le nouvel état lui donne à cet
     * instant-là, puis suit ses images clés. offset : instant du nouvel état où il commence. overlay : action en calque
     * (une pièce qu'elle n'anime pas garde l'état de fond). resume : retour à l'état de fond après un calque (une pièce
     * déjà sur l'état de fond n'est pas touchée).
     */
    private void switchState(ModelFile.State next, int offset, boolean holdEnd, boolean overlay, boolean resume) {
        state = next;
        hold = holdEnd;
        stateStart = now - offset;
        layered = overlay;
        if (next == loop) {
            loopStart = now - offset;
        }
        int until = now + BLEND_TICKS;
        List<List<EntityData<?>>> updates = new ArrayList<>(ids.length);
        int[] updated = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            Part part = model.parts().get(i);
            Track track = part.stateTrack(next.index());
            if (overlay && track == null) {
                continue;
            }
            if (resume && partState[i] == loop) {
                continue;
            }
            partState[i] = next;
            partStart[i] = now - offset;
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
        for (int i = 0; i < ids.length; i++) {
            if (now < poseDue[i]) {
                continue;
            }
            ModelFile.State st = partState[i];
            Track track = model.parts().get(i).stateTrack(st.index());
            if (track == null || track.keys() == 1) {
                // La pièce garde une seule pose dans cet état : elle l'a reçue au changement d'état
                poseDue[i] = Integer.MAX_VALUE;
                continue;
            }
            int local = now - partStart[i];
            Affine target;
            int duration;
            if (st.once()) {
                if (local >= st.length()) {
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

    private float yaw() {
        return options.yawFromLocation() ? entity.getLocation().getYaw() : entity.getBodyYaw();
    }

    /** Orientation : renvoyée quand le boss a assez tourné, lissée par le client ; figée une fois mort. */
    private void turn() {
        if (options.predictTurn()) {
            turnPredicted();
            return;
        }
        float yaw = yaw();
        if (shown.isEmpty()) {
            sentYaw = yaw;
            return;
        }
        if (hold || now < nextTurnTick || Math.abs(Location.normalizeYaw(yaw - sentYaw)) < TURN_THRESHOLD) {
            return;
        }
        sentYaw = yaw;
        nextTurnTick = now + TURN_INTERVAL;
        sendRotation(yaw);
    }

    /**
     * Rotation prédite : le client fait toujours le trajet en turnTicks ticks ; on lui donne pour cible l'orientation
     * qu'aura le boss à la fin de ce trajet, au rythme où il tourne. Tant que ce rythme ne change pas (le dragon qui
     * tourne autour de sa proie), une rotation par trajet suffit ; un écart de plus de TURN_THRESHOLD degrés avec ce
     * que le client montre en déclenche une tout de suite.
     */
    private void turnPredicted() {
        float raw = yaw();
        if (!haveLastYaw) smoothYaw = raw;
        float jump = Location.normalizeYaw(raw - smoothYaw);
        // Demi-tour d'un coup (téléportation, face à sa cible) : suivi sans lissage
        smoothYaw = Math.abs(jump) > PREDICT_JUMP ? raw : smoothYaw + jump * PREDICT_SMOOTHING;
        float yaw = smoothYaw;
        int d = Math.max(1, options.turnTicks());
        float rate = haveLastYaw ? Location.normalizeYaw(yaw - lastYaw) : 0.0f;
        lastYaw = yaw;
        haveLastYaw = true;
        if (Math.abs(rate) > PREDICT_JUMP) {
            rate = 0.0f;
        }
        yawRate = yawRate * 0.5f + rate * 0.5f;
        float k = Math.min(1.0f, (now - shownAt) / (float) d);
        float client = shownFrom + Location.normalizeYaw(shownTo - shownFrom) * k;
        sentYaw = client;
        if (shown.isEmpty()) {
            shownFrom = yaw;
            shownTo = yaw;
            shownAt = now - d;
            sentYaw = yaw;
            return;
        }
        if (hold || now - shownAt < PREDICT_GAP) {
            return;
        }
        float error = Math.abs(Location.normalizeYaw(yaw - client));
        boolean ended = now - shownAt >= d;
        boolean turning = Math.abs(yawRate) > 0.5f;
        if (error < PREDICT_THRESHOLD && !(ended && turning)) {
            return;
        }
        float target = yaw + yawRate * d;
        shownFrom = client;
        shownTo = target;
        shownAt = now;
        sendRotation(target);
    }

    private void sendRotation(float yaw) {
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
        int[] riders = new int[ids.length];
        System.arraycopy(ids, 1, riders, 0, ids.length - 1);
        riders[ids.length - 1] = labelId;
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
            packets.add(new WrapperPlayServerSpawnEntity(labelId, Optional.of(labelUuid), EntityTypes.TEXT_DISPLAY,
                position, 0.0f, 0.0f, 0.0f, 0, Optional.of(ZERO)));
            packets.add(new WrapperPlayServerEntityMetadata(labelId, labelData()));
            packets.add(new WrapperPlayServerSetPassengers(ids[0], riders));
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

    /** Le nom : texte face au joueur, au-dessus de la tête du modèle. */
    private List<EntityData<?>> labelData() {
        List<EntityData<?>> data = new ArrayList<>(3);
        data.add(new EntityData<>(Part.TEXT, EntityDataTypes.ADV_COMPONENT, label == null ? Component.empty() : label));
        data.add(new EntityData<>(Part.BILLBOARD, EntityDataTypes.BYTE, BILLBOARD_CENTER));
        data.add(new EntityData<>(Part.TRANSLATION, EntityDataTypes.VECTOR3F,
            new com.github.retrooper.packetevents.util.Vector3f(0, originOffset() + top * scale + LABEL_GAP, 0)));
        return data;
    }

    private int[] allIds() {
        int[] all = new int[ids.length + 1];
        System.arraycopy(ids, 0, all, 0, ids.length);
        all[ids.length] = labelId;
        return all;
    }

    /** Toutes les métadonnées d'une pièce, pour son apparition ; « tiny » : réduite à un point (préchargement). */
    private List<EntityData<?>> fullData(int index, boolean tiny) {
        Part part = model.parts().get(index);
        List<EntityData<?>> data = new ArrayList<>(part.staticData().size() + 6);
        data.addAll(part.staticData());
        data.add(new EntityData<>(Part.POS_ROT_DURATION, EntityDataTypes.INT, Math.max(1, options.turnTicks())));
        Affine affine = placed(poses[index]);
        data.add(new EntityData<>(Part.TRANSLATION, EntityDataTypes.VECTOR3F, vector(affine.translation())));
        data.add(new EntityData<>(Part.LEFT_ROTATION, EntityDataTypes.QUATERNION, quaternion(affine.left())));
        data.add(new EntityData<>(Part.SCALE, EntityDataTypes.VECTOR3F,
            vector(tiny ? new Vector3f(affine.scale()).mul(TINY) : affine.scale())));
        data.add(new EntityData<>(Part.RIGHT_ROTATION, EntityDataTypes.QUATERNION, quaternion(affine.right())));
        return data;
    }

    /**
     * Décalage vertical de l'origine du modèle sous le point de passager du boss (au-dessus de sa tête) : toute la
     * hauteur de l'entité pour un modèle aux pieds du boss, la moitié pour un modèle centré sur sa hitbox.
     */
    private float originOffset() {
        return (float) (-entity.getHeight() * (1.0 - options.originHeight()));
    }

    /**
     * Transformation d'une pièce chez le client : sa pose dans le modèle, à la taille voulue, tournée pour faire face à
     * l'avant du boss, et descendue sous le point de passager du boss jusqu'à l'origine voulue.
     */
    private Affine placed(Affine pose) {
        return pose.placed(new Vector3f(0, originOffset(), 0), facing, scale);
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

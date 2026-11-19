package org.Cry_Legende.sHEggDragonBoss.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Mouvement propre à une pièce d'un modèle : ses images clés sur un cycle qui se répète. Chaque image clé donne la
 * transformation de la pièce à cet instant. Le serveur envoie la prochaine image clé avec sa durée, le client fait le
 * trajet seul. La première image clé est à l'instant 0 : c'est la pose du modèle au repos. Immuable.
 */
public final class Track {

    private static final float TOLERANCE = 1.0e-4f;

    private final int loop;
    private final int[] ticks;
    private final Affine[] poses;
    private final boolean turnsRight;

    private Track(int loop, int[] ticks, Affine[] poses) {
        this.loop = loop;
        this.ticks = ticks;
        this.poses = poses;
        boolean right = false;
        for (Affine pose : poses) {
            right |= !pose.right().equals(poses[0].right());
        }
        this.turnsRight = right;
    }

    /**
     * Piste d'une pièce à partir de ses poses successives (instants croissants, le premier à 0, tous avant la fin du
     * cycle). Les poses que la pièce traverse déjà en allant de la précédente à la suivante sont retirées : elles ne
     * coûtent alors aucun paquet. Rend null si la pièce ne bouge pas.
     */
    public static Track of(int loop, int[] ticks, Affine[] poses) {
        List<Integer> kept = new ArrayList<>();
        kept.add(0);
        for (int k = 1; k < ticks.length; k++) {
            int previous = kept.getLast();
            boolean last = k + 1 == ticks.length;
            int nextTick = last ? loop : ticks[k + 1];
            Affine next = last ? poses[0] : poses[k + 1];
            float progress = (ticks[k] - ticks[previous]) / (float) (nextTick - ticks[previous]);
            if (!poses[k].near(poses[previous].lerp(next, progress), TOLERANCE)) {
                kept.add(k);
            }
        }
        if (kept.size() == 1) {
            return null;
        }
        int[] keptTicks = new int[kept.size()];
        Affine[] keptPoses = new Affine[kept.size()];
        // Deux poses égales gardent le même objet : une pièce qui attend se reconnaît par une simple comparaison
        for (int i = 0; i < keptTicks.length; i++) {
            int k = kept.get(i);
            keptTicks[i] = ticks[k];
            keptPoses[i] = i > 0 && poses[k].equals(keptPoses[i - 1]) ? keptPoses[i - 1] : poses[k];
        }
        if (keptPoses[keptPoses.length - 1].equals(keptPoses[0])) {
            keptPoses[keptPoses.length - 1] = keptPoses[0];
        }
        return new Track(loop, keptTicks, keptPoses);
    }

    /**
     * Piste d'une pièce dans un état : images clés déjà choisies par l'atelier, gardées telles quelles. Rend null si
     * la pièce garde sa pose de base pendant tout l'état.
     */
    public static Track ofState(int loop, int[] ticks, Affine[] poses, Affine base) {
        if (ticks.length == 1 && poses[0].near(base, 1.0e-5f)) {
            return null;
        }
        return new Track(loop, ticks, poses);
    }

    /** Durée du cycle (ticks). */
    public int loop() {
        return loop;
    }

    /** Nombre d'images clés du cycle. */
    public int keys() {
        return ticks.length;
    }

    /** Une rotation droite change pendant le cycle (sinon elle n'est jamais renvoyée). */
    public boolean turnsRight() {
        return turnsRight;
    }

    /** Indice de l'image clé en cours à cet instant du cycle (la dernière passée). */
    public int keyAt(int local) {
        int key = 0;
        while (key + 1 < ticks.length && ticks[key + 1] <= local) {
            key++;
        }
        return key;
    }

    /** Instant de la prochaine image clé après celui-ci ; la fin du cycle (loop) ramène à la première. */
    public int nextTick(int local) {
        int key = keyAt(local);
        return key + 1 < ticks.length ? ticks[key + 1] : loop;
    }

    /** Instant de la dernière image clé du cycle. */
    public int lastTick() {
        return ticks[ticks.length - 1];
    }

    /** Pose de la dernière image clé (celle qu'un état joué une fois et tenu garde à la fin). */
    public Affine lastPose() {
        return poses[poses.length - 1];
    }

    /** Transformation de la pièce à cet instant du cycle : celle d'une image clé, ou le point du trajet entre deux. */
    public Affine at(int local) {
        int key = keyAt(local);
        if (ticks[key] == local) {
            return poses[key];
        }
        boolean last = key + 1 == ticks.length;
        Affine next = last ? poses[0] : poses[key + 1];
        if (next == poses[key]) {
            return next;
        }
        int end = last ? loop : ticks[key + 1];
        return poses[key].lerp(next, (local - ticks[key]) / (float) (end - ticks[key]));
    }
}

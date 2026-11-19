package org.Cry_Legende.sHEggDragonBoss.model;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apparences successives d'une pièce (textures d'une tête qui cligne, bloc qui s'allume...) sur un cycle qui se
 * répète, indépendant de son mouvement. Chaque étape donne l'apparence affichée jusqu'à l'étape suivante ; la première,
 * à l'instant 0, est celle du modèle au repos. Immuable.
 */
public final class Looks {

    private final int loop;
    private final int[] ticks;
    private final EntityData<?>[] looks;

    private Looks(int loop, int[] ticks, EntityData<?>[] looks) {
        this.loop = loop;
        this.ticks = ticks;
        this.looks = looks;
    }

    /**
     * Cycle d'une pièce à partir de son apparence à chaque pose (instants croissants, le premier à 0, tous avant la fin
     * du cycle). Seuls les changements sont gardés ; deux poses de même identifiant montrent la même apparence et
     * partagent le même objet. Rend null si l'apparence ne change jamais.
     */
    public static Looks of(int loop, int[] ticks, EntityData<?>[] looks, String[] ids) {
        List<Integer> kept = new ArrayList<>();
        kept.add(0);
        for (int k = 1; k < ticks.length; k++) {
            if (!ids[k].equals(ids[kept.getLast()])) {
                kept.add(k);
            }
        }
        if (kept.size() == 1) {
            return null;
        }
        int[] keptTicks = new int[kept.size()];
        EntityData<?>[] keptLooks = new EntityData<?>[kept.size()];
        Map<String, EntityData<?>> same = new HashMap<>();
        for (int i = 0; i < keptTicks.length; i++) {
            int k = kept.get(i);
            keptTicks[i] = ticks[k];
            keptLooks[i] = same.computeIfAbsent(ids[k], id -> looks[k]);
        }
        return new Looks(loop, keptTicks, keptLooks);
    }

    /** Durée du cycle (ticks). */
    public int loop() {
        return loop;
    }

    /** Nombre d'étapes du cycle. */
    public int keys() {
        return ticks.length;
    }

    /** Indice de l'étape en cours à cet instant du cycle. */
    public int keyAt(int local) {
        int key = 0;
        while (key + 1 < ticks.length && ticks[key + 1] <= local) {
            key++;
        }
        return key;
    }

    /** Instant de la prochaine étape après celui-ci ; la fin du cycle (loop) ramène à la première. */
    public int nextTick(int local) {
        int key = keyAt(local);
        return key + 1 < ticks.length ? ticks[key + 1] : loop;
    }

    /** Apparence affichée à partir de cette étape. */
    public EntityData<?> look(int key) {
        return looks[key];
    }
}

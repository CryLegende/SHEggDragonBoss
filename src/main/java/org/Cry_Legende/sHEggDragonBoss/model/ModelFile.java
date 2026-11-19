package org.Cry_Legende.sHEggDragonBoss.model;

import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modèle d'un boss fait dans Blockbench : pièces (têtes de joueur) dans le repère du boss, pieds à l'origine, avant
 * vers -z, et ses états (idle, walk, attaque...), lus dans un fichier de commandes /summon suivies de lignes @state et
 * @track. Les pièces ne sont pas recentrées : l'origine du modèle est celle de l'entité qui le porte.
 */
public record ModelFile(String name, List<Part> parts, Map<String, State> states) {

    /** État du modèle : numéro (pour {@link Part#stateTrack(int)}), durée en ticks, joué une fois ou en boucle. */
    public record State(int index, String name, int length, boolean once) {
    }

    public static ModelFile read(String name, String content) {
        SummonImporter.Source source = SummonImporter.split(content);
        if (source.base().isEmpty()) {
            throw new IllegalArgumentException("aucune commande /summon");
        }
        List<SummonImporter.RawPart> raw = SummonImporter.parse(source.base());
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("aucune entité d'affichage visible");
        }
        // Repère gardé tel quel : un cadre nul a un centre nul
        SummonImporter.Bounds origin = new SummonImporter.Bounds(new Vector3f(), new Vector3f());
        List<Part> parts = new ArrayList<>(raw.size());
        for (SummonImporter.RawPart part : raw) {
            parts.add(Part.of(part));
        }
        Map<String, State> states = new LinkedHashMap<>();
        Track[][] byState = new Track[source.states().size()][];
        for (int s = 0; s < byState.length; s++) {
            SummonImporter.State state = source.states().get(s);
            byState[s] = SummonImporter.stateTracks(state, raw, origin);
            states.put(state.name(), new State(s, state.name(), state.length(), state.once()));
        }
        for (int i = 0; i < parts.size(); i++) {
            Track[] tracks = new Track[byState.length];
            for (int s = 0; s < byState.length; s++) {
                tracks[s] = byState[s][i];
            }
            parts.set(i, parts.get(i).withStates(tracks));
        }
        return new ModelFile(name, Collections.unmodifiableList(parts), Collections.unmodifiableMap(states));
    }

    public State state(String name) {
        return states.get(name);
    }
}

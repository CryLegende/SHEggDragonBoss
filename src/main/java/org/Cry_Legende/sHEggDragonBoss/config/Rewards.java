package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.Material;

import java.util.List;

/**
 * Récompenses de mort d'un boss. Elles vont toutes à celui qui porte le coup fatal ({player}) ; sans tueur, seuls les objets
 * sont lâchés.
 * <ul>
 *   <li>{@code xp} : points d'expérience donnés au tueur ;</li>
 *   <li>{@code commands} : lancées une fois pour le tueur ;</li>
 *   <li>{@code rolls} et {@code rollCommands} : les commandes de tirage au sort sont lancées {@code rolls} fois pour le tueur ;</li>
 *   <li>{@code items} : lâchés sur place, une fois.</li>
 * </ul>
 */
public record Rewards(
    int xp,
    int rolls,
    List<String> commands,
    List<String> rollCommands,
    List<ItemReward> items
) {

    /** Un objet de récompense ; {@code name} est nul et {@code lore} vide quand l'objet n'est pas personnalisé. */
    public record ItemReward(Material material, int amount, String name, List<String> lore) {
    }
}

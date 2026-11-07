package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.Material;

import java.util.List;

/**
 * Récompenses de mort d'un boss.
 * <ul>
 *   <li>{@code xp} : réserve d'expérience partagée entre les participants, au prorata de leur contribution ;</li>
 *   <li>un <b>participant</b> est un joueur dont la contribution (dégâts infligés, cœurs brisés) atteint
 *       {@code minContributionPercent} % des PV max du boss ;</li>
 *   <li>{@code guaranteedCommands} : lancées une fois pour chaque participant ({player} = le participant) ;</li>
 *   <li>{@code participantCommands} : lancées {@code rolls} fois pour chaque participant (les tirages au sort) ;</li>
 *   <li>{@code mvpCommands} : lancées une fois pour le participant qui a le plus contribué ;</li>
 *   <li>{@code commands} : lancées une fois ({player} = celui qui a porté le coup fatal) ;</li>
 *   <li>{@code items} : lâchés sur place, une fois.</li>
 * </ul>
 */
public record Rewards(
    int xp,
    double minContributionPercent,
    int rolls,
    List<String> guaranteedCommands,
    List<String> participantCommands,
    List<String> mvpCommands,
    List<String> commands,
    List<ItemReward> items
) {

    /** Un objet de récompense ; {@code name} est nul et {@code lore} vide quand l'objet n'est pas personnalisé. */
    public record ItemReward(Material material, int amount, String name, List<String> lore) {
    }
}

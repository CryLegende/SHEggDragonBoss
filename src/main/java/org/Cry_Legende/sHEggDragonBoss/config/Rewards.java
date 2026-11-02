package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.Material;

import java.util.List;

/**
 * Récompenses de mort d'un boss : XP donnée au tueur, objets lâchés sur place, commandes lancées par la console.
 */
public record Rewards(int xp, List<ItemReward> items, List<String> commands) {

    /** Un objet de récompense ; {@code name} est nul et {@code lore} vide quand l'objet n'est pas personnalisé. */
    public record ItemReward(Material material, int amount, String name, List<String> lore) {
    }
}

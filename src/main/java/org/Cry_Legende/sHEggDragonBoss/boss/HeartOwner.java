package org.Cry_Legende.sHEggDragonBoss.boss;

import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Le boss auquel appartiennent des cœurs : prévenu quand un de ses cœurs est brisé.
 */
public interface HeartOwner {

    /** Identifiant de l'entité du boss. */
    UUID bossId();

    /** Un cœur vient d'être brisé par {@code player}. */
    void onHeartDestroyed(Player player);
}

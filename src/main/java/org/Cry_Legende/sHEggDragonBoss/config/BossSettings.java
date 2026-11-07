package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Set;

/**
 * Ce que tous les boss ont en commun dans la configuration : de quoi calculer les PV, filtrer les dégâts
 * et traiter la mort.
 */
public interface BossSettings {

    String name();

    double health();

    DynamicStats dynamicStats();

    Set<EntityDamageEvent.DamageCause> immuneCauses();

    /** Aucun coup ne retire plus de ce pourcentage des PV max du boss (0 : pas de plafond). */
    double maxHitPercent();

    Sfx hurtSound();

    Sfx deathSound();

    DeathSettings death();

    Rewards rewards();
}

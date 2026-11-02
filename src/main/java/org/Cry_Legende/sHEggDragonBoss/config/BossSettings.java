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

    Sfx hurtSound();

    Sfx deathSound();

    Rewards rewards();
}

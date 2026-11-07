package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.Material;

/**
 * La mort d'un boss : au coup fatal il est retenu pendant {@code durationTicks} (il tremble, s'élève ou s'affaisse, tourne
 * sur lui-même, des particules l'entourent), puis un final éclate avant que le coup de grâce ne tombe. Ce qui est lâché ou
 * donné à ce moment (récompenses) est réglé dans {@link Rewards}.
 *
 * @param shake       amplitude du tremblement à la fin de la mort, en blocs
 * @param lift        hauteur gagnée en fin de mort, en blocs (négatif : le boss s'affaisse)
 * @param spinDegrees vitesse de rotation sur lui-même atteinte à la fin, en degrés par tick (0 : aucune)
 * @param debris      matériau des éclats projetés au final (nul : aucun)
 */
public record DeathSettings(
    boolean enabled,
    int durationTicks,
    double shake,
    double lift,
    double spinDegrees,
    Material debris,
    int debrisCount,
    Sfx startSound,
    Sfx pulseSound,
    int pulseIntervalTicks,
    double pulsePitchGrowth,
    Fx auraFx,
    Fx burstFx,
    Fx ringFx,
    Fx pillarFx,
    double ringRadius,
    double pillarHeight,
    Sfx finaleSound,
    Sfx finaleExtraSound,
    Sfx victorySound
) {
}

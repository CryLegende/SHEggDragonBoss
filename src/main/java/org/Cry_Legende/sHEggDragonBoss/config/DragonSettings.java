package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.Particle;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.List;
import java.util.Set;

/**
 * Réglages du boss n°1 (le « Jeune Ender Dragon », un Phantom sans IA que le plugin fait voler : il tourne autour d'un
 * joueur, crache des boules de feu, plonge sur lui, et rugit à chaque seuil de PV).
 */
public record DragonSettings(
    EntityType entityType,
    String name,
    String barTitle,
    String roarBarTitle,
    BarColor barColor,
    BarStyle barStyle,
    double health,
    DynamicStats dynamicStats,
    int phantomSize,
    double attackDamage,
    double meleeTruePercent,
    double meleeRangeSquared,
    int attackCooldownTicks,
    double speedMultiplier,
    double maxHitPercent,
    Set<EntityDamageEvent.DamageCause> immuneCauses,
    List<Double> phases,
    double phaseSpeedup,
    double phaseSpeedBonus,
    Flight flight,
    RangedAttack rangedAttack,
    Dive dive,
    Roar roar,
    SpawnExplosion spawnExplosion,
    Sfx spawnSound,
    Sfx ambientSound,
    int ambientSoundDelaySeconds,
    Sfx deathSound,
    Sfx hurtSound,
    Sfx blockedSound,
    AmbientParticles ambientParticles,
    Rewards rewards,
    DeathSettings death,
    Texts texts,
    Model model
) implements BossSettings {

    /**
     * Modèle 3D qui remplace l'apparence du dragon : têtes de joueur affichées par paquets (packetevents), comme le
     * Creaking. Le boss est alors une entité porteuse invisible ({@code carrier}, un Ghast : le Phantom garde ses yeux
     * lumineux même invisible) à la taille {@code entityScale} (hitbox). file : nom du fichier (models/&lt;file&gt;.txt du
     * jar) ; scale : taille du modèle ; facing : rotation du modèle (degrés) ; turnTicks : lissage des rotations (ticks) ;
     * heave : amplitude de la montée et de la descente du corps en vol (1 = celle du modèle, 0 : aucune) ; mouth :
     * bouche du modèle (blocs à l'échelle 1 : droite, haut, avant depuis le centre), d'où partent les boules de feu.
     */
    public record Model(boolean enabled, String file, double scale, EntityType carrier, double entityScale, double facing,
                        int turnTicks, double heave, double[] mouth) {
    }

    /** Vol en cercle autour de la cible : rayon, hauteur au-dessus de ses yeux, vitesse angulaire en degrés par tick. */
    public record Flight(double orbitRadius, double orbitHeight, double orbitDegreesPerTick) {
    }

    /** Salve de boules de feu : {@code fireballs} au départ, {@code fireballsPerPhase} de plus à chaque phase franchie. */
    public record RangedAttack(boolean enabled, int cooldownTicks, double minRangeSquared, double maxRange, int fireballs,
                               int fireballsPerPhase, Sfx sound) {
    }

    /**
     * Le plongeon : le dragon se cabre au-dessus d'un joueur, fixe l'endroit visé pendant {@code lockTicks} (un cercle de
     * particules le montre), puis fond dessus. L'impact blesse tout ce qui est à moins de {@code hitRadius} blocs ; le dragon
     * reste ensuite étourdi {@code stunTicks} ticks, plus vulnérable ({@code stunDamageMultiplier}).
     */
    public record Dive(
        boolean enabled,
        int cooldownTicks,
        double range,
        int warningTicks,
        int lockTicks,
        double speed,
        double hitRadius,
        double damage,
        double truePercent,
        double knockback,
        int stunTicks,
        double stunDamageMultiplier,
        Sfx warningSound,
        Fx warningFx,
        Sfx impactSound,
        Fx impactFx,
        Fx stunFx
    ) {
    }

    /** Le rugissement d'un changement de phase : le dragon est invulnérable {@code durationTicks} ticks puis souffle tout autour de lui. */
    public record Roar(
        boolean enabled,
        int durationTicks,
        double radius,
        double damage,
        double truePercent,
        double knockbackHorizontal,
        double knockbackVertical,
        Sfx startSound,
        Sfx blastSound,
        Fx auraFx,
        Fx blastFx
    ) {
    }

    public record SpawnExplosion(boolean enabled, float power, boolean setFire, boolean breakBlocks) {
    }

    public record AmbientParticles(boolean enabled, Particle particle, int count, double speed, int intervalTicks) {
    }

    /** Textes du combat, lus dans messages.yml (bosses.dragon.*). Un message vide n'est pas envoyé. */
    public record Texts(String roarTitle, String roarSubtitle, String roarChat, String diveWarning) {
    }
}

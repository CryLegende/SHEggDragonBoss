package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.Particle;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.EntityType;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.Set;

/**
 * Réglages du boss n°1 (le « Jeune Ender Dragon », un Phantom sans IA qui poursuit les joueurs de l'île).
 */
public record DragonSettings(
    EntityType entityType,
    String name,
    String barTitle,
    BarColor barColor,
    BarStyle barStyle,
    double health,
    DynamicStats dynamicStats,
    int phantomSize,
    double attackDamage,
    double meleeRangeSquared,
    int attackCooldownTicks,
    double speedMultiplier,
    double stoppingDistanceSquared,
    Set<EntityDamageEvent.DamageCause> immuneCauses,
    RangedAttack rangedAttack,
    SpawnExplosion spawnExplosion,
    Sfx spawnSound,
    Sfx ambientSound,
    int ambientSoundDelaySeconds,
    Sfx deathSound,
    Sfx hurtSound,
    AmbientParticles ambientParticles,
    Rewards rewards
) implements BossSettings {

    public record RangedAttack(boolean enabled, int cooldownTicks, double minRangeSquared, Sfx sound) {
    }

    public record SpawnExplosion(boolean enabled, float power, boolean setFire, boolean breakBlocks) {
    }

    public record AmbientParticles(boolean enabled, Particle particle, int count, double speed, int intervalTicks) {
    }
}

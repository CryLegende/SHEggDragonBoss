package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Set;

/**
 * Réglages du boss n°2 (le Creaking : IA vanilla, phases d'invulnérabilité protégées par des cœurs à détruire,
 * onde de choc et résine collante).
 */
public record CreakingSettings(
    String name,
    Bar bar,
    double health,
    DynamicStats dynamicStats,
    double attackDamage,
    double movementSpeed,
    double armor,
    double armorToughness,
    double knockbackResistance,
    double followRange,
    boolean assistActivation,
    int checkIntervalTicks,
    Set<EntityDamageEvent.DamageCause> immuneCauses,
    Arena arena,
    List<Phase> phases,
    Hearts hearts,
    Shockwave shockwave,
    Resin resin,
    Sfx spawnSound,
    Fx spawnFx,
    Sfx deathSound,
    Sfx hurtSound,
    Sfx shieldStartSound,
    Sfx shieldEndSound,
    Sfx shieldBlockedSound,
    Fx shieldAuraFx,
    int shieldAuraIntervalTicks,
    Rewards rewards,
    Messages messages
) implements BossSettings {

    /** Barre de boss : apparence normale puis apparence pendant une phase d'invulnérabilité. */
    public record Bar(String title, BarColor color, BarStyle style, String shieldTitle, BarColor shieldColor, BarStyle shieldStyle) {
    }

    /** Limites de l'arène : sous {@code maxDepthBelowCenter} blocs sous le centre de l'île, le boss est ramené au centre. */
    public record Arena(double maxDepthBelowCenter, double recenterOffsetY) {
    }

    /** Une phase d'invulnérabilité : déclenchée sous {@code healthPercent} % des PV, protégée par {@code hearts} cœurs. */
    public record Phase(double healthPercent, int hearts) {
    }

    /** Les cœurs à détruire pendant une phase. */
    public record Hearts(
        int hitsToBreak,
        double hitCooldownSeconds,
        double minDistance,
        double maxDistance,
        int verticalRange,
        double minSpacing,
        int placementAttempts,
        int placementRetries,
        Sfx placeSound,
        Fx placeFx,
        Sfx hitSound,
        Fx hitFx,
        Sfx breakSound,
        Fx breakFx
    ) {
    }

    public record Shockwave(
        boolean enabled,
        int cooldownTicks,
        double radius,
        double damage,
        double knockbackHorizontal,
        double knockbackVertical,
        double shieldFrequencyFactor,
        Sfx sound,
        Fx ringFx
    ) {
    }

    public record Resin(
        boolean enabled,
        int cooldownTicks,
        double radius,
        boolean duringShield,
        List<Effect> effects,
        Sfx sound,
        Fx fx
    ) {
    }

    public record Effect(PotionEffectType type, int amplifier, int durationTicks) {
    }

    /** Messages du combat ; {hearts} = cœurs restants, {phase} / {phases} = phase en cours / nombre de phases, {player} = le joueur qui a brisé le cœur. */
    public record Messages(String shieldStart, String heartDestroyed, String shieldEnd) {
    }
}

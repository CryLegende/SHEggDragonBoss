package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Set;

/**
 * Réglages du boss n°2 (le Creaking : IA vanilla, phases d'invulnérabilité protégées par des cœurs à frapper,
 * le Noir qui aveugle les joueurs pour que le boss puisse bouger, onde de choc et résine collante).
 */
public record CreakingSettings(
    String name,
    Bar bar,
    double health,
    DynamicStats dynamicStats,
    double attackDamage,
    double meleeTruePercent,
    double movementSpeed,
    double armor,
    double armorToughness,
    double knockbackResistance,
    double followRange,
    boolean assistActivation,
    int checkIntervalTicks,
    Set<EntityDamageEvent.DamageCause> immuneCauses,
    double maxHitPercent,
    Arena arena,
    List<Phase> phases,
    Hearts hearts,
    Shockwave shockwave,
    Resin resin,
    Blackout blackout,
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
    Texts texts
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

    /** Les cœurs à détruire pendant une phase, et l'aide donnée aux joueurs pour les trouver et comprendre comment les briser. */
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
        Fx breakFx,
        String hitActionbar,
        Fx markerFx,
        int markerIntervalTicks,
        double markerHeight,
        String guideActionbar,
        int guideIntervalTicks,
        double guideRange,
        boolean glow,
        int glowColor
    ) {
    }

    public record Shockwave(
        boolean enabled,
        int cooldownTicks,
        double radius,
        double damage,
        double truePercent,
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

    /** Un effet de potion du Noir ; sa durée est celle de la fenêtre du Noir. */
    public record BlindEffect(PotionEffectType type, int amplifier) {
    }

    /**
     * Le Noir : le boss aveugle les joueurs proches. Un joueur aveuglé ne fige plus le Creaking du regard, alors le boss
     * se téléporte derrière lui et le frappe après un court délai (esquivable en s'éloignant, ou en retirant l'aveuglement).
     */
    public record Blackout(
        boolean enabled,
        int cooldownTicks,
        double phaseCooldownFactor,
        int afterPhaseDelayTicks,
        double radius,
        int telegraphTicks,
        int durationTicks,
        int firstBlinkDelayTicks,
        int blinkIntervalTicks,
        double blinkDistance,
        int strikeDelayTicks,
        double strikeReach,
        double strikeDamage,
        double strikeTruePercent,
        double strikeKnockback,
        List<BlindEffect> effects,
        Sfx telegraphSound,
        Fx telegraphFx,
        Sfx blinkSound,
        Fx blinkFx,
        Sfx strikeSound,
        Fx strikeFx,
        Sfx dodgeSound
    ) {
    }

    /**
     * Textes du combat, lus dans messages.yml (bosses.creaking.*). Un message vide n'est pas envoyé.
     */
    public record Texts(
        String spawnHint,
        String shieldStart,
        String shieldTitle,
        String shieldSubtitle,
        String heartHint,
        String heartDestroyed,
        String shieldEnd,
        String blackoutTitle,
        String blackoutSubtitle,
        String dodge
    ) {
    }
}

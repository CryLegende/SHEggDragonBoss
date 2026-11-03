package org.Cry_Legende.sHEggDragonBoss.config;

import org.Cry_Legende.sHEggDragonBoss.boss.BossType;
import org.bukkit.Material;

import java.util.List;

/**
 * Un rituel : un bloc sur lequel on fait un clic droit avec un objet, en quantité suffisante, pour lancer
 * le compte à rebours qui fait apparaître le boss {@code type}.
 */
public record RitualSettings(
    BossType type,
    boolean enabled,
    Material block,
    Material item,
    int requiredAmount,
    int countdownSeconds,
    double spawnOffsetY,
    Hologram hologram,
    Animation animation,
    Sounds sounds,
    Particles particles,
    String startedMessage,
    String lockedMessage
) {

    /** Hologramme au-dessus du bloc ; les textes (messages.yml) acceptent %current%, %required% et, pour le compte à rebours, %time%. */
    public record Hologram(
        String namePrefix,
        List<String> progress,
        List<String> countdown,
        double height,
        boolean textShadow,
        double countdownHeight,
        double countdownScale
    ) {
    }

    /** Animation du bloc (BlockDisplay) pendant le compte à rebours. */
    public record Animation(double growth, double levitation, double levitationSpeed, double wobble) {
    }

    public record Sounds(Sfx addItem, Sfx start, Sfx tick, double tickPitchGrowth) {
    }

    public record Particles(Fx addItem, Fx countdown, double countdownGrowth) {
    }
}

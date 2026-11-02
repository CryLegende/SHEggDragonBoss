package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Un son réglable (nom, volume, hauteur). Un son nul est un son désactivé : le jouer ne fait rien.
 */
public record Sfx(Sound sound, float volume, float pitch) {

    public static final Sfx SILENT = new Sfx(null, 0f, 0f);

    public void play(Location at) {
        play(at, 0f);
    }

    /** Joue le son, la hauteur étant augmentée de {@code pitchBonus} (compte à rebours qui monte dans les aigus). */
    public void play(Location at, float pitchBonus) {
        if (sound == null || at == null) return;
        World world = at.getWorld();
        if (world == null) return;
        world.playSound(at, sound, volume, pitch + pitchBonus);
    }

    public void playFor(Player player) {
        if (sound == null || player == null) return;
        player.playSound(player.getLocation(), sound, volume, pitch);
    }
}

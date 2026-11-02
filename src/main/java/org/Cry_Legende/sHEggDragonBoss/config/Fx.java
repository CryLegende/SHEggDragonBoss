package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;

/**
 * Un effet de particules réglable. Une particule nulle est un effet désactivé : le jouer ne fait rien.
 * {@code spread} est la dispersion (en blocs) autour du point, sur les trois axes.
 */
public record Fx(Particle particle, int count, double spread, double speed) {

    public static final Fx NONE = new Fx(null, 0, 0.0, 0.0);

    public void spawn(Location at) {
        spawn(at, count, spread, spread, spread);
    }

    public void spawn(Location at, int amount, double spreadX, double spreadY, double spreadZ) {
        if (particle == null || amount <= 0 || at == null) return;
        World world = at.getWorld();
        if (world == null) return;
        world.spawnParticle(particle, at, amount, spreadX, spreadY, spreadZ, speed);
    }
}

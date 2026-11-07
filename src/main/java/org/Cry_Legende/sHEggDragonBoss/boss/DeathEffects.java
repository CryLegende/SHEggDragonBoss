package org.Cry_Legende.sHEggDragonBoss.boss;

import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.config.DeathSettings;
import org.Cry_Legende.sHEggDragonBoss.config.Fx;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Collection;

/**
 * Le final de la mort d'un boss : une explosion de particules, des éclats, deux anneaux qui s'élargissent, une colonne de
 * lumière qui monte, et les sons de la victoire. Joué juste avant le coup de grâce ; l'animation continue quelques ticks
 * après la mort du boss (elle ne dépend plus de lui).
 */
public final class DeathEffects {

    /** Nombre de ticks d'animation des anneaux et de la colonne. */
    private static final int STEPS = 16;
    /** Écart, en blocs, entre deux points de la colonne de lumière. */
    private static final double PILLAR_STEP = 0.6;

    private DeathEffects() {
    }

    public static void finale(SHEggDragonBoss plugin, DeathSettings settings, Location center, Collection<Player> audience) {
        World world = center.getWorld();
        if (world == null) return;

        settings.burstFx().spawn(center);
        if (settings.debris() != null && settings.debrisCount() > 0) {
            world.spawnParticle(Particle.BLOCK, center, settings.debrisCount(), 1.2, 1.0, 1.2, 0.15, settings.debris().createBlockData());
        }
        settings.finaleSound().play(center);
        settings.finaleExtraSound().play(center);
        for (Player player : audience) settings.victorySound().playFor(player);

        // Le point est copié : l'animation vit plus longtemps que l'appelant
        Location at = center.clone();
        new BukkitRunnable() {
            private int step = 0;

            @Override
            public void run() {
                step++;
                double progress = step / (double) STEPS;
                ring(settings.ringFx(), at, settings.ringRadius() * progress);
                // Le second anneau suit le premier, plus petit
                if (progress > 0.3) ring(settings.ringFx(), at, settings.ringRadius() * (progress - 0.3));
                pillar(settings.pillarFx(), at, settings.pillarHeight() * progress);
                if (step >= STEPS) cancel();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Anneau horizontal de {@code fx.count()} points, un peu sous le centre (au niveau du sol du boss). */
    private static void ring(Fx fx, Location center, double radius) {
        if (fx.particle() == null || fx.count() <= 0 || radius <= 0.1) return;
        for (int i = 0; i < fx.count(); i++) {
            double angle = 2.0 * Math.PI * i / fx.count();
            fx.spawn(center.clone().add(Math.cos(angle) * radius, -0.8, Math.sin(angle) * radius), 1, 0.0, 0.0, 0.0);
        }
    }

    /** Colonne verticale de particules, du centre jusqu'à {@code height} blocs au-dessus. */
    private static void pillar(Fx fx, Location center, double height) {
        if (fx.particle() == null || fx.count() <= 0 || height <= 0.1) return;
        for (double y = 0.0; y <= height; y += PILLAR_STEP) {
            fx.spawn(center.clone().add(0, y, 0), fx.count(), fx.spread(), 0.0, fx.spread());
        }
    }
}

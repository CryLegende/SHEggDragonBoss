package org.Cry_Legende.sHEggDragonBoss.ritual;

import org.Cry_Legende.sHEggDragonBoss.config.RitualSettings;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;

/**
 * Compte à rebours d'un rituel : fait grossir et léviter le bloc, met l'hologramme à jour chaque seconde,
 * joue le son et les particules, puis fait apparaître le boss.
 */
public class RitualTask extends BukkitRunnable {

    private final BossManager bossManager;
    private final RitualProcess process;
    private final RitualSettings ritual;
    private int ticksRun = 0;

    public RitualTask(BossManager bossManager, RitualProcess process) {
        this.bossManager = bossManager;
        this.process = process;
        this.ritual = process.getRitual();
    }

    @Override
    public void run() {
        int totalTicks = ritual.countdownSeconds() * 20;

        if (ticksRun >= totalTicks) {
            bossManager.spawnBoss(process);
            this.cancel();
            return;
        }

        float progress = (float) ticksRun / totalTicks;
        RitualSettings.Animation animation = ritual.animation();

        BlockDisplay animator = process.getBlockAnimator();
        if (animator != null && !animator.isDead()) {
            Transformation transformation = animator.getTransformation();
            float scale = 1.0f + (progress * (float) animation.growth());
            float levitation = (float) (Math.sin(ticksRun * animation.levitationSpeed()) * animation.levitation() * progress);
            float randomAngle = (float) (Math.random() - 0.5) * (float) animation.wobble() * progress;
            transformation.getTranslation().set(-scale / 2, levitation, -scale / 2);
            transformation.getScale().set(scale, scale, scale);
            transformation.getLeftRotation().set(new AxisAngle4f(randomAngle, 0, 1, 0));
            animator.setTransformation(transformation);
        }

        if (ticksRun % 20 == 0) {
            int remainingSeconds = (totalTicks - ticksRun) / 20;
            if (remainingSeconds > 0) {
                process.updateHologram(remainingSeconds);
            }
            Location center = process.getLocation().clone().add(0.5, 0.5, 0.5);
            ritual.sounds().tick().play(center, progress * (float) ritual.sounds().tickPitchGrowth());
        }

        Location particleLocation = process.getLocation().clone().add(0.5, 0.5, 0.5);
        RitualSettings.Particles particles = ritual.particles();
        int particleAmount = (int) (particles.countdown().count() * (1 + progress * (float) particles.countdownGrowth()));
        particles.countdown().spawn(particleLocation, particleAmount, particles.countdown().spread(), particles.countdown().spread(), particles.countdown().spread());

        ticksRun++;
    }
}

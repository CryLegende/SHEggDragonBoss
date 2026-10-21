package org.Cry_Legende.sHEggDragonBoss.tasks;

import org.Cry_Legende.sHEggDragonBoss.hatching.HatchingProcess;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.ConfigManager;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;

public class HatchingTask extends BukkitRunnable {

    private final BossManager bossManager;
    private final HatchingProcess process;
    private final ConfigManager configManager;
    private int ticksRun = 0;

    public HatchingTask(BossManager bossManager, HatchingProcess process, ConfigManager configManager) {
        this.bossManager = bossManager;
        this.process = process;
        this.configManager = configManager;
    }

    @Override
    public void run() {
        int totalTicks = configManager.getCountdownSeconds() * 20;

        if (ticksRun >= totalTicks) {
            bossManager.spawnBoss(process);
            this.cancel();
            return;
        }

        BlockDisplay eggAnimator = process.getEggAnimator();
        if (eggAnimator != null && !eggAnimator.isDead()) {
            Transformation transformation = eggAnimator.getTransformation();
            float progress = (float) ticksRun / totalTicks;
            float scale = 1.0f + (progress * 1.5f);
            float levitation = (float) (Math.sin(ticksRun / 8.0) * 0.35 * progress);
            float randomAngle = (float) (Math.random() - 0.5) * 0.2f * progress;
            transformation.getTranslation().set(-scale / 2, levitation, -scale / 2);
            transformation.getScale().set(scale, scale, scale);
            transformation.getLeftRotation().set(new AxisAngle4f(randomAngle, 0, 1, 0));
            eggAnimator.setTransformation(transformation);
        }

        if (ticksRun % 20 == 0) {
            int remainingSeconds = (totalTicks - ticksRun) / 20;
            if (remainingSeconds > 0) {
                process.updateHologram(configManager, remainingSeconds);
            }
            Location eggCenterLocation = process.getLocation().clone().add(0.5, 0.5, 0.5);
            Sound tickSound = configManager.getCountdownTickSound();
            float volume = configManager.getCountdownTickVolume();
            float pitch = configManager.getCountdownTickPitch() + ((float) ticksRun / totalTicks);
            eggCenterLocation.getWorld().playSound(eggCenterLocation, tickSound, volume, pitch);
        }

        Location particleLocation = process.getLocation().clone().add(0.5, 0.5, 0.5);
        int particleAmount = (int) (10 * (1 + ((float) ticksRun / totalTicks) * 4));
        particleLocation.getWorld().spawnParticle(Particle.PORTAL, particleLocation, particleAmount, 0.8, 0.8, 0.8, 0.1);

        ticksRun++;
    }
}

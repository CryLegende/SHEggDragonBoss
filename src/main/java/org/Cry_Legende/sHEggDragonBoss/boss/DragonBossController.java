package org.Cry_Legende.sHEggDragonBoss.boss;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.config.DragonSettings;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * Boss n°1 : le « Jeune Ender Dragon ». Un Phantom sans IA, déplacé à la main vers le joueur le plus proche,
 * qui tire des boules de feu de loin et frappe de près.
 */
public class DragonBossController extends BossController {

    private final DragonSettings settings;

    private long lastRangedAttackTick = 0;
    private long lastMeleeAttackTick = 0;

    private DragonBossController(SHEggDragonBoss plugin, BossManager bossManager, Island island, LivingEntity boss, double maxHealth) {
        super(plugin, bossManager, island, boss, maxHealth,
            plugin.getConfigManager().dragon().name(),
            plugin.getConfigManager().dragon().barColor(),
            plugin.getConfigManager().dragon().barStyle());
        this.settings = plugin.getConfigManager().dragon();
    }

    /** Fait apparaître un nouveau boss (éclosion) ou réveille un boss dormant à {@code location}. */
    public static DragonBossController spawn(SHEggDragonBoss plugin, BossManager bossManager, Island island, Location location,
                                             double maxHealth, double currentHealth, StartMode mode) {
        LivingEntity entity = createEntity(plugin.getConfigManager().dragon(), location, maxHealth, currentHealth);
        DragonBossController controller = new DragonBossController(plugin, bossManager, island, entity, maxHealth);
        controller.start(mode);
        return controller;
    }

    /** Reprend un boss déjà présent dans le monde (redémarrage, chunk rechargé). */
    public static DragonBossController adopt(SHEggDragonBoss plugin, BossManager bossManager, Island island, LivingEntity existing, double maxHealth) {
        DragonBossController controller = new DragonBossController(plugin, bossManager, island, existing, maxHealth);
        controller.start(StartMode.RESTORED);
        return controller;
    }

    @Override
    public BossType type() {
        return BossType.DRAGON;
    }

    @Override
    protected void onStart(StartMode mode) {
        String islandName = islandLabel();
        switch (mode) {
            case FIRST_SPAWN -> {
                playSpawnEffects();
                plugin.getLogManager().log(String.format("Un boss est apparu sur %s (Vie: %.1f/%.1f).", islandName, boss.getHealth(), maxHealth));
            }
            case WOKEN_FROM_DORMANT ->
                plugin.getLogManager().log(String.format("Un boss dormant a été réveillé sur %s (Vie: %.1f/%.1f).", islandName, boss.getHealth(), maxHealth));
            case RESTORED ->
                plugin.getLogManager().log(String.format("Un boss a été restauré sur %s après un redémarrage.", islandName));
        }
    }

    @Override
    protected void onTick() {
        if (target != null) {
            updateAttackingMovement();
            updateAmbientParticles();
            updateAmbientSound();
        }
    }

    private void updateAttackingMovement() {
        // La cible est choisie une fois par seconde : elle a pu changer de monde ou se déconnecter depuis
        if (target == null || !target.isOnline() || !target.getWorld().equals(boss.getWorld())) return;
        Location bossLoc = boss.getLocation();
        Location targetLoc = target.getEyeLocation();
        Vector direction = targetLoc.toVector().subtract(bossLoc.toVector());
        double distanceSquared = bossLoc.distanceSquared(targetLoc);
        DragonSettings.RangedAttack ranged = settings.rangedAttack();

        if (ranged.enabled() && distanceSquared > ranged.minRangeSquared() && tickCounter >= lastRangedAttackTick + ranged.cooldownTicks()) {
            fireRangedAttack(direction.clone());
            lastRangedAttackTick = tickCounter;
        } else if (distanceSquared < ranged.minRangeSquared() && tickCounter >= lastMeleeAttackTick + settings.attackCooldownTicks()) {
            if (distanceSquared < settings.meleeRangeSquared()) target.damage(settings.attackDamage(), boss);
            lastMeleeAttackTick = tickCounter;
        }

        Location nextPos = boss.getLocation().clone();
        if (distanceSquared > settings.stoppingDistanceSquared()) {
            double speed = settings.speedMultiplier() * 0.1;
            nextPos.add(direction.clone().normalize().multiply(speed));
        }

        nextPos.setYaw((float) Math.toDegrees(Math.atan2(-direction.getX(), direction.getZ())));
        nextPos.setPitch((float) Math.toDegrees(Math.atan2(-direction.getY(), Math.sqrt(Math.pow(direction.getX(), 2) + Math.pow(direction.getZ(), 2)))));

        if (island.isInside(nextPos)) {
            boss.teleport(nextPos);
        } else {
            boss.teleport(island.getCenter(dimension(boss.getWorld().getEnvironment())));
        }
    }

    private void fireRangedAttack(Vector direction) {
        Vector mainDir = direction.normalize();
        Location spawnPos = boss.getEyeLocation().add(mainDir.clone().multiply(1.5));

        DragonFireball mainFireball = boss.getWorld().spawn(spawnPos, DragonFireball.class);
        mainFireball.setDirection(mainDir);
        mainFireball.setShooter(boss);

        Vector side = mainDir.clone().crossProduct(new Vector(0, 1, 0)).normalize().multiply(0.4);
        DragonFireball leftFireball = boss.getWorld().spawn(spawnPos, DragonFireball.class);
        leftFireball.setDirection(mainDir.clone().add(side).normalize());
        leftFireball.setShooter(boss);

        DragonFireball rightFireball = boss.getWorld().spawn(spawnPos, DragonFireball.class);
        rightFireball.setDirection(mainDir.clone().subtract(side).normalize());
        rightFireball.setShooter(boss);

        settings.rangedAttack().sound().play(boss.getLocation());
    }

    private void updateAmbientParticles() {
        DragonSettings.AmbientParticles ambient = settings.ambientParticles();
        if (ambient.enabled() && ambient.particle() != null && tickCounter % ambient.intervalTicks() == 0) {
            boss.getWorld().spawnParticle(ambient.particle(), boss.getLocation().add(0, boss.getHeight() / 2, 0), ambient.count(), boss.getWidth() / 2, boss.getHeight() / 2, boss.getWidth() / 2, ambient.speed());
        }
    }

    private void updateAmbientSound() {
        if (tickCounter % (settings.ambientSoundDelaySeconds() * 20) == 0) {
            settings.ambientSound().play(boss.getLocation());
        }
    }

    private void playSpawnEffects() {
        Location loc = boss.getLocation();
        DragonSettings.SpawnExplosion explosion = settings.spawnExplosion();
        if (explosion.enabled())
            loc.getWorld().createExplosion(loc, explosion.power(), explosion.setFire(), explosion.breakBlocks(), boss);
        settings.spawnSound().play(loc);
    }

    @Override
    protected String barTitle() {
        return settings.barTitle()
            .replace("{name}", settings.name())
            .replace("{current_health}", String.valueOf((int) Math.round(boss.getHealth())))
            .replace("{max_health}", String.valueOf((int) Math.round(maxHealth)));
    }

    @Override
    protected BarColor barColor() {
        return settings.barColor();
    }

    @Override
    protected BarStyle barStyle() {
        return settings.barStyle();
    }

    private static LivingEntity createEntity(DragonSettings settings, Location location, double maxHealth, double currentHealth) {
        LivingEntity spawned = (LivingEntity) location.getWorld().spawnEntity(location, settings.entityType());
        spawned.setAI(false);
        spawned.setCollidable(false);
        spawned.setSilent(true);
        spawned.setCustomName(settings.name());
        spawned.setCustomNameVisible(true);
        spawned.setPersistent(true);
        spawned.getPersistentDataContainer().set(SHEggDragonBoss.BOSS_KEY, PersistentDataType.BYTE, (byte) 1);
        spawned.getPersistentDataContainer().set(SHEggDragonBoss.BOSS_TYPE_KEY, PersistentDataType.STRING, BossType.DRAGON.id());
        if (spawned instanceof Phantom) ((Phantom) spawned).setSize(settings.phantomSize());
        AttributeInstance maxHealthAttr = spawned.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) maxHealthAttr.setBaseValue(maxHealth);
        spawned.setHealth(Math.min(currentHealth, maxHealth));
        return spawned;
    }
}

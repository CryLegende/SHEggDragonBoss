package org.Cry_Legende.sHEggDragonBoss.boss;

import org.bukkit.World;
import com.bgsoftware.superiorskyblock.api.world.Dimension;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.managers.BossDataManager;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BossController extends BukkitRunnable {

    private final ConfigManager configManager;
    private final Island island;
    private final LivingEntity boss;
    private final BossBar bossBar;
    private final double maxHealth;

    private Player target;
    private Player externalTarget;
    private int tickCounter = 0;
    private long lastRangedAttackTick = 0;
    private long lastMeleeAttackTick = 0;

    private Location lastKnownLocation;
    private double lastKnownHealth;

    public BossController(SHEggDragonBoss plugin, BossManager bossManager, Island island, Location spawnLocation, double maxHealth, double currentHealth, boolean isFirstSpawn) {
        this.configManager = plugin.getConfigManager();
        this.island = island;
        this.maxHealth = maxHealth;
        this.boss = createBossEntity(spawnLocation, maxHealth, currentHealth);
        this.bossBar = createBossBar();
        this.lastKnownLocation = boss.getLocation();
        this.lastKnownHealth = boss.getHealth();

        String islandName = (island != null && island.getOwner() != null) ? "l'île de " + island.getOwner().getName() : "une île inconnue";
        if (isFirstSpawn) {
            playSpawnEffects();
            plugin.getLogManager().log(String.format("Un boss est apparu sur %s (Vie: %.1f/%.1f).", islandName, currentHealth, maxHealth));
        } else {
            plugin.getLogManager().log(String.format("Un boss dormant a été réveillé sur %s (Vie: %.1f/%.1f).", islandName, currentHealth, maxHealth));
        }

        bossManager.registerActiveBoss(this);
        this.runTaskTimer(plugin, 0L, 1L);
    }

    public BossController(SHEggDragonBoss plugin, BossManager bossManager, Island island, LivingEntity existingBoss, double maxHealth) {
        this.configManager = plugin.getConfigManager();
        this.island = island;
        this.maxHealth = maxHealth;
        this.boss = existingBoss;
        this.bossBar = createBossBar();
        this.lastKnownLocation = boss.getLocation();
        this.lastKnownHealth = boss.getHealth();
        String islandName = (island != null && island.getOwner() != null) ? "l'île de " + island.getOwner().getName() : "une île inconnue";
        plugin.getLogManager().log(String.format("Un boss a été restauré sur %s après un redémarrage.", islandName));
        bossManager.registerActiveBoss(this);
        this.runTaskTimer(plugin, 0L, 1L);
    }

    @Override
    public void run() {
        if (!boss.isValid() || boss.isDead()) {
            this.cancel();
            return;
        }

        tickCounter++;

        if (tickCounter % 20 == 0) {
            updateTarget();
            updateBossBar();
        }

        if (target != null) {
            updateAttackingMovement();
            updateAmbientParticles();
            updateAmbientSound();
        }

        this.lastKnownLocation = boss.getLocation();
        this.lastKnownHealth = boss.getHealth();
    }

    @Override
    public synchronized void cancel() throws IllegalStateException {
        super.cancel();
        bossBar.removeAll();
    }

    public void setExternalTarget(Player player) {
        this.externalTarget = player;
    }

    private void updateTarget() {
        // Une île peut couvrir plusieurs dimensions : seule la cible du même monde que le boss est mesurable
        World bossWorld = boss.getWorld();
        List<Player> islandMembers = island.getIslandMembers(true).stream()
            .map(SuperiorPlayer::asPlayer)
            .filter(p -> p != null && p.isOnline() && !p.isDead() && p.getGameMode() != org.bukkit.GameMode.SPECTATOR && p.getWorld().equals(bossWorld) && island.isInside(p.getLocation()))
            .collect(Collectors.toList());
        Player validExternalTarget = (externalTarget != null && externalTarget.isOnline() && !externalTarget.isDead() && externalTarget.getWorld().equals(bossWorld)) ? externalTarget : null;
        this.target = Stream.concat(islandMembers.stream(), Stream.ofNullable(validExternalTarget))
            .filter(Objects::nonNull).distinct()
            .min(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation())))
            .orElse(null);
    }

    private void updateAttackingMovement() {
        // La cible est choisie une fois par seconde : elle a pu changer de monde ou se déconnecter depuis
        if (target == null || !target.isOnline() || !target.getWorld().equals(boss.getWorld())) return;
        Location bossLoc = boss.getLocation();
        Location targetLoc = target.getEyeLocation();
        Vector direction = targetLoc.toVector().subtract(bossLoc.toVector());
        double distanceSquared = bossLoc.distanceSquared(targetLoc);

        if (configManager.isRangedAttackEnabled() && distanceSquared > configManager.getRangedAttackMinRangeSquared() && tickCounter >= lastRangedAttackTick + configManager.getRangedAttackCooldownTicks()) {
            fireRangedAttack(direction.clone());
            lastRangedAttackTick = tickCounter;
        } else if (distanceSquared < configManager.getRangedAttackMinRangeSquared() && tickCounter >= lastMeleeAttackTick + configManager.getBossAttackCooldownTicks()) {
            if (distanceSquared < 4.0 * 4.0) target.damage(configManager.getBossAttackDamage(), boss);
            lastMeleeAttackTick = tickCounter;
        }

        Location nextPos = boss.getLocation().clone();
        if (distanceSquared > configManager.getBossStoppingDistanceSquared()) {
            double speed = configManager.getBossSpeedMultiplier() * 0.1;
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

        boss.getWorld().playSound(boss.getLocation(), configManager.getRangedAttackSound(), configManager.getRangedAttackSoundVolume(), configManager.getRangedAttackSoundPitch());
    }

    private void updateAmbientParticles() {
        if (configManager.isAmbientParticlesEnabled() && tickCounter % 4 == 0) {
            boss.getWorld().spawnParticle(configManager.getAmbientParticleType(), boss.getLocation().add(0, boss.getHeight() / 2, 0), configManager.getAmbientParticleCount(), boss.getWidth() / 2, boss.getHeight() / 2, boss.getWidth() / 2, configManager.getAmbientParticleSpeed());
        }
    }

    private void updateAmbientSound() {
        if (tickCounter % (configManager.getBossSoundDelay() * 20) == 0) {
            boss.getWorld().playSound(boss.getLocation(), configManager.getBossAmbientSound(), configManager.getBossSoundVolume(), configManager.getBossSoundPitch());
        }
    }

    private LivingEntity createBossEntity(Location location, double maxHealth, double currentHealth) {
        LivingEntity spawned = (LivingEntity) location.getWorld().spawnEntity(location, configManager.getBossEntityType());
        spawned.setAI(false);
        spawned.setCollidable(false);
        spawned.setSilent(true);
        spawned.setCustomName(configManager.getBossName());
        spawned.setCustomNameVisible(true);
        spawned.setPersistent(true);
        spawned.getPersistentDataContainer().set(SHEggDragonBoss.BOSS_KEY, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        if (spawned instanceof Phantom) ((Phantom) spawned).setSize(configManager.getPhantomSize());
        AttributeInstance maxHealthAttr = spawned.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) maxHealthAttr.setBaseValue(maxHealth);
        spawned.setHealth(Math.min(currentHealth, maxHealth));
        return spawned;
    }

    private BossBar createBossBar() {
        return Bukkit.createBossBar(configManager.getBossName(), BarColor.PURPLE, BarStyle.SOLID);
    }

    private void playSpawnEffects() {
        Location loc = boss.getLocation();
        if (configManager.isSpawnExplosionEnabled())
            loc.getWorld().createExplosion(loc, configManager.getSpawnExplosionPower(), configManager.isSpawnExplosionSetFire(), configManager.shouldSpawnExplosionBreakBlocks(), boss);
        loc.getWorld().playSound(loc, configManager.getBossSpawnSound(), configManager.getBossSpawnSoundVolume(), configManager.getBossSpawnSoundPitch());
    }

    private void updateBossBar() {
        double currentHealth = boss.getHealth();
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, currentHealth / maxHealth)));
        bossBar.setTitle(configManager.getBossBarTitle().replace("{name}", configManager.getBossName()).replace("{current_health}", String.valueOf((int) Math.round(currentHealth))).replace("{max_health}", String.valueOf((int) Math.round(maxHealth))));
        Set<Player> viewers = island.getIslandMembers(true).stream().filter(SuperiorPlayer::isOnline).map(SuperiorPlayer::asPlayer).filter(Objects::nonNull).collect(Collectors.toSet());
        if (externalTarget != null && externalTarget.isOnline()) viewers.add(externalTarget);
        new ArrayList<>(bossBar.getPlayers()).stream().filter(p -> !viewers.contains(p)).forEach(bossBar::removePlayer);
        viewers.stream().filter(p -> !bossBar.getPlayers().contains(p)).forEach(bossBar::addPlayer);
    }

    public LivingEntity getBoss() {
        return boss;
    }

    public Island getIsland() {
        return island;
    }

    public UUID getUniqueId() {
        return boss.getUniqueId();
    }

    public BossDataManager.BossData getBossData() {
        return new BossDataManager.BossData(island.getUniqueId(), this.lastKnownLocation, this.lastKnownHealth, maxHealth);
    }

    // SSB2 2026 : getCenter prend une Dimension et non plus un World.Environment
    private static Dimension dimension(World.Environment env) {
        for (Dimension d : Dimension.values()) {
            if (d.getEnvironment() == env) return d;
        }
        return Dimension.getByName("NORMAL");
    }
}

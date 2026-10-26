package org.Cry_Legende.sHEggDragonBoss.managers;

import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class ConfigManager {

    public record ItemReward(Material material, int amount, String name, List<String> lore) {
    }

    private final SHEggDragonBoss plugin;
    private FileConfiguration config;

    // Messages
    private String noPermission;
    private String notOnIsland;
    private String startHatching;
    private String countdownStart;
    private String alreadyHatching;
    private String dragonActiveOnIsland;

    // Hologrammes
    private List<String> hologramProgress;
    private List<String> hologramCountdown;
    private double hologramHeight;
    private boolean hologramTextShadow;
    private double hologramCountdownHeight;
    private double hologramCountdownScale;

    // Configuration de l'éclosion
    private Material requiredItem;
    private int requiredShards;
    private int countdownSeconds;

    // Configuration du Boss
    private EntityType bossEntityType;
    private String bossName;
    private String bossBarTitle;
    private double bossHealth;
    private int phantomSize;
    private double bossAttackDamage;
    private int bossAttackCooldownTicks;
    private double bossSpeedMultiplier;
    private double bossStoppingDistanceSquared; // Stocké au carré pour l'optimisation
    private double bossArmor;
    private double bossArmorToughness;
    private boolean rangedAttackEnabled;
    private int rangedAttackCooldownTicks;
    private double rangedAttackMinRangeSquared;
    private Sound rangedAttackSound;
    private float rangedAttackSoundVolume;
    private float rangedAttackSoundPitch;
    private boolean spawnExplosionEnabled;
    private float spawnExplosionPower;
    private boolean spawnExplosionSetFire;
    private boolean spawnExplosionBreakBlocks;
    private Sound bossSpawnSound;
    private float bossSpawnSoundVolume;
    private float bossSpawnSoundPitch;
    private Sound bossAmbientSound;
    private float bossSoundVolume;
    private float bossSoundPitch;
    private int bossSoundDelay;
    private Sound bossDeathSound;
    private float bossDeathSoundVolume;
    private float bossDeathSoundPitch;
    private Sound bossHurtSound;
    private float bossHurtSoundVolume;
    private float bossHurtSoundPitch;
    private boolean ambientParticlesEnabled;
    private Particle ambientParticleType;
    private int ambientParticleCount;
    private double ambientParticleSpeed;
    private boolean rangedAttackParticlesEnabled;
    private Particle rangedAttackParticleType;
    private int rangedAttackParticleCount;
    private double rangedAttackParticleSpeed;
    private int xpReward;
    private List<ItemReward> itemRewards;
    private List<String> deathCommands;
    private boolean dynamicStatsEnabled;
    private double healthPerIslandLevel;
    private double healthPerIslandMember;

    // Configuration des sons
    private Sound addShardSound;
    private float addShardVolume;
    private float addShardPitch;
    private Sound hatchingStartSound;
    private float hatchingStartVolume;
    private float hatchingStartPitch;
    private Sound countdownTickSound;
    private float countdownTickVolume;
    private float countdownTickPitch;

    // Permission d'île
    private String islandPermissionName;


    public ConfigManager(SHEggDragonBoss plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();

        noPermission = loadString("messages.no-permission");
        notOnIsland = loadString("messages.not-on-island");
        startHatching = loadString("messages.start-hatching");
        countdownStart = loadString("messages.countdown-start");
        alreadyHatching = loadString("messages.already-hatching");
        dragonActiveOnIsland = loadString("messages.dragon-active-on-island");

        hologramProgress = loadStringList("messages.hologram-progress");
        hologramCountdown = loadStringList("messages.hologram-countdown");
        hologramHeight = config.getDouble("hologram-settings.height", 1.3);
        hologramTextShadow = config.getBoolean("hologram-settings.text-shadow", true);
        hologramCountdownHeight = config.getDouble("hologram-settings.countdown-height", 2.5);
        hologramCountdownScale = config.getDouble("hologram-settings.countdown-scale", 3.0);

        try {
            requiredItem = Material.valueOf(config.getString("hatching.required-item", "ECHO_SHARD").toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("L'item '" + config.getString("hatching.required-item") + "' dans la config est invalide. Utilisation de ECHO_SHARD par défaut.");
            requiredItem = Material.ECHO_SHARD;
        }
        requiredShards = config.getInt("hatching.required-shards", 50);
        countdownSeconds = config.getInt("hatching.countdown-seconds", 10);

        try {
            bossEntityType = EntityType.valueOf(config.getString("boss.entity-type", "PHANTOM").toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().severe("Le type d'entité '" + config.getString("boss.entity-type") + "' est invalide ! Utilisation de PHANTOM par défaut.");
            bossEntityType = EntityType.PHANTOM;
        }
        if (!bossEntityType.isSpawnable() || bossEntityType.getEntityClass() == null || !LivingEntity.class.isAssignableFrom(bossEntityType.getEntityClass())) {
            // Le boss est converti en LivingEntity à l'apparition : un autre type ferait planter l'éclosion
            plugin.getLogger().severe("Le type d'entité '" + bossEntityType + "' ne peut pas servir de boss ! Utilisation de PHANTOM par défaut.");
            bossEntityType = EntityType.PHANTOM;
        }
        bossName = loadString("boss.name");
        bossBarTitle = loadString("boss.bossbar-title");
        bossHealth = config.getDouble("boss.health", 300.0);
        phantomSize = config.getInt("boss.phantom-size", 20);
        bossAttackDamage = config.getDouble("boss.attack-damage", 12.0);
        bossAttackCooldownTicks = config.getInt("boss.attack-cooldown-ticks", 40);
        bossSpeedMultiplier = config.getDouble("boss.movement-speed-multiplier", 1.5);
        double stoppingDistance = config.getDouble("boss.stopping-distance", 2.5);
        bossStoppingDistanceSquared = stoppingDistance * stoppingDistance;
        bossArmor = config.getDouble("boss.armor", 10.0);
        bossArmorToughness = config.getDouble("boss.armor-toughness", 5.0);

        rangedAttackEnabled = config.getBoolean("boss.ranged-attack.enabled", true);
        rangedAttackCooldownTicks = config.getInt("boss.ranged-attack.cooldown-ticks", 100);
        rangedAttackMinRangeSquared = config.getDouble("boss.ranged-attack.min-range-squared", 16.0);
        rangedAttackSound = sound(config.getString("boss.ranged-attack.sound.sound", "ENTITY_ENDER_DRAGON_SHOOT"), Sound.ENTITY_ENDER_DRAGON_SHOOT);
        rangedAttackSoundVolume = (float) config.getDouble("boss.ranged-attack.sound.volume", 3.0);
        rangedAttackSoundPitch = (float) config.getDouble("boss.ranged-attack.sound.pitch", 1.0);

        spawnExplosionEnabled = config.getBoolean("boss.spawn-explosion.enabled", true);
        spawnExplosionPower = (float) config.getDouble("boss.spawn-explosion.power", 8.0);
        spawnExplosionSetFire = config.getBoolean("boss.spawn-explosion.set-fire", false);
        spawnExplosionBreakBlocks = config.getBoolean("boss.spawn-explosion.break-blocks", true);

        bossSpawnSound = sound(config.getString("boss.spawn-sound.sound", "ENTITY_ENDER_DRAGON_DEATH"), Sound.ENTITY_ENDER_DRAGON_DEATH);
        bossSpawnSoundVolume = (float) config.getDouble("boss.spawn-sound.volume", 2.0);
        bossSpawnSoundPitch = (float) config.getDouble("boss.spawn-sound.pitch", 0.1);
        bossAmbientSound = sound(config.getString("boss.ambient-sound.sound", "ENTITY_WARDEN_AMBIENT"), Sound.ENTITY_WARDEN_AMBIENT);
        bossSoundVolume = (float) config.getDouble("boss.ambient-sound.volume", 2.0);
        bossSoundPitch = (float) config.getDouble("boss.ambient-sound.pitch", 0.1);
        // Au moins 1 seconde : 0 provoquerait une division par zéro à chaque tick
        bossSoundDelay = Math.max(1, config.getInt("boss.ambient-sound.delay", 3));
        bossDeathSound = sound(config.getString("boss.death-sound.sound", "ENTITY_WITHER_DEATH"), Sound.ENTITY_WITHER_DEATH);
        bossDeathSoundVolume = (float) config.getDouble("boss.death-sound.volume", 2.0);
        bossDeathSoundPitch = (float) config.getDouble("boss.death-sound.pitch", 0.5);
        bossHurtSound = sound(config.getString("boss.hurt-sound.sound", "ENTITY_WITHER_HURT"), Sound.ENTITY_WITHER_HURT);
        bossHurtSoundVolume = (float) config.getDouble("boss.hurt-sound.volume", 2.0);
        bossHurtSoundPitch = (float) config.getDouble("boss.hurt-sound.pitch", 0.1);

        ambientParticlesEnabled = config.getBoolean("boss.particles.ambient.enabled", true);
        try {
            ambientParticleType = Particle.valueOf(config.getString("boss.particles.ambient.particle-type", "REVERSE_PORTAL").toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Type de particule d'ambiance invalide. Utilisation de REVERSE_PORTAL par défaut.");
            ambientParticleType = Particle.REVERSE_PORTAL;
        }
        ambientParticleCount = config.getInt("boss.particles.ambient.count", 5);
        ambientParticleSpeed = config.getDouble("boss.particles.ambient.speed", 0.05);

        rangedAttackParticlesEnabled = config.getBoolean("boss.particles.ranged-attack.enabled", true);
        try {
            rangedAttackParticleType = Particle.valueOf(config.getString("boss.particles.ranged-attack.particle-type", "EXPLOSION_LARGE").toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Type de particule d'attaque à distance invalide. Utilisation de EXPLOSION_LARGE par défaut.");
            rangedAttackParticleType = Particle.EXPLOSION;
        }
        rangedAttackParticleCount = config.getInt("boss.particles.ranged-attack.count", 1);
        rangedAttackParticleSpeed = config.getDouble("boss.particles.ranged-attack.speed", 0.1);

        xpReward = config.getInt("boss.rewards.xp-reward", 12000);

        itemRewards = new ArrayList<>();
        List<Map<?, ?>> itemList = config.getMapList("boss.rewards.items");
        for (Map<?, ?> itemMap : itemList) {
            try {
                String materialName = (String) itemMap.get("material");
                if (materialName == null) continue;

                Material material = Material.valueOf(materialName.toUpperCase());

                int amount = 1;
                Object amountObj = itemMap.get("amount");
                if (amountObj instanceof Number) {
                    amount = ((Number) amountObj).intValue();
                }

                String name = null;
                if (itemMap.containsKey("name")) {
                    name = ChatColor.translateAlternateColorCodes('&', (String) itemMap.get("name"));
                }

                List<String> lore = new ArrayList<>();
                if (itemMap.containsKey("lore")) {
                    lore = ((List<String>) itemMap.get("lore")).stream()
                        .map(line -> ChatColor.translateAlternateColorCodes('&', line))
                        .collect(Collectors.toList());
                }

                itemRewards.add(new ItemReward(material, amount, name, lore));
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur lors du chargement d'un item de récompense. Assurez-vous que le format est correct. Item ignoré. Erreur: " + e.getMessage());
            }
        }


        deathCommands = config.getStringList("boss.rewards.commands");

        dynamicStatsEnabled = config.getBoolean("boss.dynamic-stats.enabled", true);
        healthPerIslandLevel = config.getDouble("boss.dynamic-stats.health-per-island-level", 5.0);
        healthPerIslandMember = config.getDouble("boss.dynamic-stats.health-per-island-member", 10.0);

        addShardSound = sound(config.getString("sounds.add-shard.sound", "ENTITY_ELDER_GUARDIAN_HURT"), Sound.ENTITY_ELDER_GUARDIAN_HURT);
        addShardVolume = (float) config.getDouble("sounds.add-shard.volume", 1.0);
        addShardPitch = (float) config.getDouble("sounds.add-shard.pitch", 0.1);

        hatchingStartSound = sound(config.getString("sounds.hatching-start.sound", "ENTITY_ENDER_DRAGON_GROWL"), Sound.ENTITY_ENDER_DRAGON_GROWL);
        hatchingStartVolume = (float) config.getDouble("sounds.hatching-start.volume", 2.0);
        hatchingStartPitch = (float) config.getDouble("sounds.hatching-start.pitch", 0.1);

        countdownTickSound = sound(config.getString("sounds.countdown-tick.sound", "ENTITY_LIGHTNING_BOLT_THUNDER"), Sound.ENTITY_LIGHTNING_BOLT_THUNDER);
        countdownTickVolume = (float) config.getDouble("sounds.countdown-tick.volume", 1.0);
        countdownTickPitch = (float) config.getDouble("sounds.countdown-tick.pitch", 0.1);

        islandPermissionName = config.getString("island-permission.name", "EGG_HATCH");
    }

    /** Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup). */
    private static Sound sound(String raw, Sound fallback) {
        Sound parsed = parseSound(raw);
        return parsed != null ? parsed : fallback;
    }

    private static Sound parseSound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    private String loadString(String path) {
        String message = config.getString(path, "");
        return ChatColor.translateAlternateColorCodes('&', message);
    }

    private List<String> loadStringList(String path) {
        List<String> list = config.getStringList(path);
        return list.stream()
            .map(line -> ChatColor.translateAlternateColorCodes('&', line))
            .collect(Collectors.toList());
    }

    // --- Getters ---
    public String getNoPermission() {
        return noPermission;
    }

    public String getNotOnIsland() {
        return notOnIsland;
    }

    public String getStartHatching() {
        return startHatching;
    }

    public String getCountdownStart() {
        return countdownStart;
    }

    public String getAlreadyHatching() {
        return alreadyHatching;
    }

    public String getDragonActiveOnIsland() {
        return dragonActiveOnIsland;
    }

    public List<String> getHologramProgress() {
        return hologramProgress;
    }

    public List<String> getHologramCountdown() {
        return hologramCountdown;
    }

    public double getHologramHeight() {
        return hologramHeight;
    }

    public boolean isHologramTextShadowEnabled() {
        return hologramTextShadow;
    }

    public double getHologramCountdownHeight() {
        return hologramCountdownHeight;
    }

    public double getHologramCountdownScale() {
        return hologramCountdownScale;
    }

    public Material getRequiredItem() {
        return requiredItem;
    }

    public int getRequiredShards() {
        return requiredShards;
    }

    public int getCountdownSeconds() {
        return countdownSeconds;
    }

    public EntityType getBossEntityType() {
        return bossEntityType;
    }

    public String getBossName() {
        return bossName;
    }

    public String getBossBarTitle() {
        return bossBarTitle;
    }

    public double getBossHealth() {
        return bossHealth;
    }

    public int getPhantomSize() {
        return phantomSize;
    }

    public double getBossAttackDamage() {
        return bossAttackDamage;
    }

    public int getBossAttackCooldownTicks() {
        return bossAttackCooldownTicks;
    }

    public double getBossSpeedMultiplier() {
        return bossSpeedMultiplier;
    }

    public double getBossStoppingDistanceSquared() {
        return bossStoppingDistanceSquared;
    }

    public double getBossArmor() {
        return bossArmor;
    }

    public double getBossArmorToughness() {
        return bossArmorToughness;
    }

    public boolean isRangedAttackEnabled() {
        return rangedAttackEnabled;
    }

    public int getRangedAttackCooldownTicks() {
        return rangedAttackCooldownTicks;
    }

    public double getRangedAttackMinRangeSquared() {
        return rangedAttackMinRangeSquared;
    }

    public Sound getRangedAttackSound() {
        return rangedAttackSound;
    }

    public float getRangedAttackSoundVolume() {
        return rangedAttackSoundVolume;
    }

    public float getRangedAttackSoundPitch() {
        return rangedAttackSoundPitch;
    }

    public boolean isSpawnExplosionEnabled() {
        return spawnExplosionEnabled;
    }

    public float getSpawnExplosionPower() {
        return spawnExplosionPower;
    }

    public boolean isSpawnExplosionSetFire() {
        return spawnExplosionSetFire;
    }

    public boolean shouldSpawnExplosionBreakBlocks() {
        return spawnExplosionBreakBlocks;
    }

    public Sound getBossSpawnSound() {
        return bossSpawnSound;
    }

    public float getBossSpawnSoundVolume() {
        return bossSpawnSoundVolume;
    }

    public float getBossSpawnSoundPitch() {
        return bossSpawnSoundPitch;
    }

    public Sound getBossAmbientSound() {
        return bossAmbientSound;
    }

    public float getBossSoundVolume() {
        return bossSoundVolume;
    }

    public float getBossSoundPitch() {
        return bossSoundPitch;
    }

    public int getBossSoundDelay() {
        return bossSoundDelay;
    }

    public Sound getBossDeathSound() {
        return bossDeathSound;
    }

    public float getBossDeathSoundVolume() {
        return bossDeathSoundVolume;
    }

    public float getBossDeathSoundPitch() {
        return bossDeathSoundPitch;
    }

    public Sound getBossHurtSound() {
        return bossHurtSound;
    }

    public float getBossHurtSoundVolume() {
        return bossHurtSoundVolume;
    }

    public float getBossHurtSoundPitch() {
        return bossHurtSoundPitch;
    }

    public boolean isAmbientParticlesEnabled() {
        return ambientParticlesEnabled;
    }

    public Particle getAmbientParticleType() {
        return ambientParticleType;
    }

    public int getAmbientParticleCount() {
        return ambientParticleCount;
    }

    public double getAmbientParticleSpeed() {
        return ambientParticleSpeed;
    }

    public boolean isRangedAttackParticlesEnabled() {
        return rangedAttackParticlesEnabled;
    }

    public Particle getRangedAttackParticleType() {
        return rangedAttackParticleType;
    }

    public int getRangedAttackParticleCount() {
        return rangedAttackParticleCount;
    }

    public double getRangedAttackParticleSpeed() {
        return rangedAttackParticleSpeed;
    }

    public int getXpReward() {
        return xpReward;
    }

    public List<ItemReward> getItemRewards() {
        return itemRewards;
    }

    public List<String> getDeathCommands() {
        return deathCommands;
    }

    public boolean isDynamicStatsEnabled() {
        return dynamicStatsEnabled;
    }

    public double getHealthPerIslandLevel() {
        return healthPerIslandLevel;
    }

    public double getHealthPerIslandMember() {
        return healthPerIslandMember;
    }

    public Sound getAddShardSound() {
        return addShardSound;
    }

    public float getAddShardVolume() {
        return addShardVolume;
    }

    public float getAddShardPitch() {
        return addShardPitch;
    }

    public Sound getHatchingStartSound() {
        return hatchingStartSound;
    }

    public float getHatchingStartVolume() {
        return hatchingStartVolume;
    }

    public float getHatchingStartPitch() {
        return hatchingStartPitch;
    }

    public Sound getCountdownTickSound() {
        return countdownTickSound;
    }

    public float getCountdownTickVolume() {
        return countdownTickVolume;
    }

    public float getCountdownTickPitch() {
        return countdownTickPitch;
    }

    public String getIslandPermissionName() {
        return islandPermissionName;
    }
}

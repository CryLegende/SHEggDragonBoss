package org.Cry_Legende.sHEggDragonBoss.managers;

import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.boss.BossType;
import org.Cry_Legende.sHEggDragonBoss.config.BossSettings;
import org.Cry_Legende.sHEggDragonBoss.config.CommonMessages;
import org.Cry_Legende.sHEggDragonBoss.config.ConfigReader;
import org.Cry_Legende.sHEggDragonBoss.config.CreakingSettings;
import org.Cry_Legende.sHEggDragonBoss.config.DragonSettings;
import org.Cry_Legende.sHEggDragonBoss.config.DynamicStats;
import org.Cry_Legende.sHEggDragonBoss.config.Fx;
import org.Cry_Legende.sHEggDragonBoss.config.Rewards;
import org.Cry_Legende.sHEggDragonBoss.config.RitualSettings;
import org.Cry_Legende.sHEggDragonBoss.config.Sfx;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Lit config.yml une fois au démarrage (le plugin n'a pas de rechargement) et en tire des réglages immuables.
 * Toute la validation passe par {@link ConfigReader} : valeur inconnue = avertissement + valeur livrée.
 */
public class ConfigManager {

    private static final Pattern HOLOGRAM_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    private final SHEggDragonBoss plugin;

    private CommonMessages common;
    private final Map<BossType, RitualSettings> rituals = new EnumMap<>(BossType.class);
    private final Map<Material, RitualSettings> ritualsByBlock = new HashMap<>();
    private DragonSettings dragon;
    private CreakingSettings creaking;
    private String islandPermissionName;

    public ConfigManager(SHEggDragonBoss plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();
        ConfigReader in = new ConfigReader(config, plugin.getLogger());

        common = new CommonMessages(
            in.text("messages.common.no-permission"),
            in.text("messages.common.not-on-island"),
            in.text("messages.common.boss-already-active"));

        rituals.clear();
        ritualsByBlock.clear();
        for (BossType type : BossType.values()) {
            RitualSettings ritual = readRitual(in, type);
            if (ritual == null) continue;
            rituals.put(type, ritual);
            if (!ritual.enabled()) continue;
            RitualSettings previous = ritualsByBlock.putIfAbsent(ritual.block(), ritual);
            if (previous != null) {
                // Deux rituels sur le même bloc seraient indistinguables : le second est désactivé
                in.warn("Les rituels '" + previous.type().id() + "' et '" + type.id() + "' utilisent le même bloc (" + ritual.block()
                    + "). Le rituel '" + type.id() + "' est désactivé.");
                rituals.put(type, disabled(ritual));
            }
        }

        dragon = readDragon(in);
        creaking = readCreaking(in);
        islandPermissionName = in.rawText("island-permission.name");
        if (islandPermissionName.isEmpty()) {
            in.warn("Le nom de la permission d'île ('island-permission.name') est vide : EGG_HATCH est utilisé.");
            islandPermissionName = "EGG_HATCH";
        }

        if (in.warnings() > 0) {
            plugin.getLogger().warning(in.warnings() + " avertissement(s) à la lecture de config.yml : corrigez le fichier, les valeurs livrées ont été utilisées.");
        }
    }

    // ------------------------------------------------------------------ rituels

    private RitualSettings readRitual(ConfigReader in, BossType type) {
        String p = "rituals." + type.id() + ".";
        boolean enabled = in.bool(p + "enabled");
        Material block = in.block(p + "block");
        Material item = in.item(p + "item");
        if (block == null || item == null) {
            plugin.getLogger().severe("Le rituel '" + type.id() + "' est désactivé : bloc ou objet inutilisable (voir les avertissements ci-dessus).");
            return null;
        }

        String prefix = in.rawText(p + "hologram.name-prefix");
        if (!HOLOGRAM_NAME.matcher(prefix).matches()) {
            in.warn("Préfixe d'hologramme invalide pour '" + p + "hologram.name-prefix' : '" + prefix
                + "' (lettres, chiffres, '_', '.' et '-' seulement, 32 caractères au plus). Utilisation de 'sHE" + type.id() + "'.");
            prefix = "sHE" + type.id();
        }

        RitualSettings.Hologram hologram = new RitualSettings.Hologram(
            prefix,
            in.textList(p + "hologram.progress"),
            in.textList(p + "hologram.countdown"),
            in.decimal(p + "hologram.height", -64.0, 64.0),
            in.bool(p + "hologram.text-shadow"),
            in.decimal(p + "hologram.countdown-height", -64.0, 64.0),
            in.decimal(p + "hologram.countdown-scale", 0.1, 100.0));

        RitualSettings.Animation animation = new RitualSettings.Animation(
            in.decimal(p + "animation.growth", 0.0, 20.0),
            in.decimal(p + "animation.levitation", 0.0, 20.0),
            in.decimal(p + "animation.levitation-speed", 0.0, 10.0),
            in.decimal(p + "animation.wobble", 0.0, 10.0));

        String s = "sounds." + type.id() + ".";
        RitualSettings.Sounds sounds = new RitualSettings.Sounds(
            in.sfx(s + "add-item"),
            in.sfx(s + "start"),
            in.sfx(s + "countdown-tick"),
            in.decimal(s + "countdown-tick.pitch-growth", 0.0, 10.0));

        RitualSettings.Particles particles = new RitualSettings.Particles(
            in.fx(p + "particles.add-item", true),
            in.fx(p + "particles.countdown", true),
            in.decimal(p + "particles.countdown.count-growth", 0.0, 100.0));

        return new RitualSettings(
            type,
            enabled,
            block,
            item,
            in.integer(p + "required-amount", 1),
            in.integer(p + "countdown-seconds", 1),
            in.decimal(p + "spawn-offset-y", -64.0, 64.0),
            hologram,
            animation,
            sounds,
            particles,
            in.text("messages." + type.id() + ".ritual-started"),
            in.text("messages." + type.id() + ".ritual-locked"));
    }

    private static RitualSettings disabled(RitualSettings r) {
        return new RitualSettings(r.type(), false, r.block(), r.item(), r.requiredAmount(), r.countdownSeconds(), r.spawnOffsetY(),
            r.hologram(), r.animation(), r.sounds(), r.particles(), r.startedMessage(), r.lockedMessage());
    }

    // ------------------------------------------------------------------ boss n°1 : dragon

    private DragonSettings readDragon(ConfigReader in) {
        String p = "bosses.dragon.";
        double stoppingDistance = in.decimal(p + "stopping-distance", 0.0);
        double meleeRange = in.decimal(p + "melee-range", 0.0);
        return new DragonSettings(
            in.livingEntityType(p + "entity-type"),
            in.text(p + "name"),
            in.text(p + "bossbar.title"),
            in.enumValue(p + "bossbar.color", BarColor.class),
            in.enumValue(p + "bossbar.style", BarStyle.class),
            in.decimal(p + "health", 1.0),
            dynamicStats(in, p),
            in.integer(p + "phantom-size", 0),
            in.decimal(p + "attack-damage", 0.0),
            meleeRange * meleeRange, // stocké au carré pour l'optimisation
            in.integer(p + "attack-cooldown-ticks", 1),
            in.decimal(p + "movement-speed-multiplier", 0.0),
            stoppingDistance * stoppingDistance, // stocké au carré pour l'optimisation
            in.damageCauses(p + "immune-damage-causes"),
            new DragonSettings.RangedAttack(
                in.bool(p + "ranged-attack.enabled"),
                in.integer(p + "ranged-attack.cooldown-ticks", 1),
                in.decimal(p + "ranged-attack.min-range-squared", 0.0),
                in.sfx(p + "ranged-attack.sound")),
            new DragonSettings.SpawnExplosion(
                in.bool(p + "spawn-explosion.enabled"),
                (float) in.decimal(p + "spawn-explosion.power", 0.0, 100.0),
                in.bool(p + "spawn-explosion.set-fire"),
                in.bool(p + "spawn-explosion.break-blocks")),
            in.sfx(p + "spawn-sound"),
            in.sfx(p + "ambient-sound"),
            // Au moins 1 seconde : 0 provoquerait une division par zéro à chaque tick
            in.integer(p + "ambient-sound.delay", 1),
            in.sfx(p + "death-sound"),
            in.sfx(p + "hurt-sound"),
            new DragonSettings.AmbientParticles(
                in.bool(p + "particles.ambient.enabled"),
                in.particle(p + "particles.ambient.particle"),
                in.integer(p + "particles.ambient.count", 0),
                in.decimal(p + "particles.ambient.speed", 0.0),
                in.integer(p + "particles.ambient.interval-ticks", 1)),
            rewards(in, p + "rewards."));
    }

    // ------------------------------------------------------------------ boss n°2 : creaking

    private CreakingSettings readCreaking(ConfigReader in) {
        String p = "bosses.creaking.";
        CreakingSettings.Bar bar = new CreakingSettings.Bar(
            in.text(p + "bossbar.title"),
            in.enumValue(p + "bossbar.color", BarColor.class),
            in.enumValue(p + "bossbar.style", BarStyle.class),
            in.text(p + "bossbar.shield-title"),
            in.enumValue(p + "bossbar.shield-color", BarColor.class),
            in.enumValue(p + "bossbar.shield-style", BarStyle.class));

        CreakingSettings.Arena arena = new CreakingSettings.Arena(
            in.decimal(p + "arena.max-depth-below-center", 0.0),
            in.decimal(p + "arena.recenter-offset-y", -64.0, 64.0));

        String h = p + "hearts.";
        CreakingSettings.Hearts hearts = new CreakingSettings.Hearts(
            in.integer(h + "hits-to-break", 1),
            in.decimal(h + "hit-cooldown-seconds", 0.0, 60.0),
            in.decimal(h + "min-distance", 0.0),
            in.decimal(h + "max-distance", 1.0),
            in.integer(h + "vertical-range", 0),
            in.decimal(h + "min-spacing", 0.0),
            in.integer(h + "placement-attempts", 1),
            in.integer(h + "placement-retries", 0),
            in.sfx(h + "place-sound"),
            in.fx(h + "place-particles", true),
            in.sfx(h + "hit-sound"),
            in.fx(h + "hit-particles", true),
            in.sfx(h + "break-sound"),
            in.fx(h + "break-particles", true));
        if (hearts.maxDistance() < hearts.minDistance()) {
            in.warn("'" + h + "max-distance' (" + hearts.maxDistance() + ") est inférieur à '" + h + "min-distance' (" + hearts.minDistance()
                + ") : les cœurs ne pourront jamais être placés.");
        }

        String sw = p + "attacks.shockwave.";
        CreakingSettings.Shockwave shockwave = new CreakingSettings.Shockwave(
            in.bool(sw + "enabled"),
            in.integer(sw + "cooldown-ticks", 1),
            in.decimal(sw + "radius", 0.5, 64.0),
            in.decimal(sw + "damage", 0.0),
            in.decimal(sw + "knockback-horizontal", 0.0, 10.0),
            in.decimal(sw + "knockback-vertical", 0.0, 10.0),
            in.decimal(sw + "shield-frequency-factor", 1.0, 100.0),
            in.sfx(sw + "sound"),
            in.fx(sw + "particles", false));

        String rs = p + "attacks.resin.";
        CreakingSettings.Resin resin = new CreakingSettings.Resin(
            in.bool(rs + "enabled"),
            in.integer(rs + "cooldown-ticks", 1),
            in.decimal(rs + "radius", 0.5, 64.0),
            in.bool(rs + "during-shield"),
            readEffects(in, rs + "effects"),
            in.sfx(rs + "sound"),
            in.fx(rs + "particles", true));

        CreakingSettings.Messages messages = new CreakingSettings.Messages(
            in.text("messages.creaking.shield-start"),
            in.text("messages.creaking.heart-destroyed"),
            in.text("messages.creaking.shield-end"));

        return new CreakingSettings(
            in.text(p + "name"),
            bar,
            in.decimal(p + "health", 1.0),
            dynamicStats(in, p),
            in.decimal(p + "attack-damage", 0.0),
            in.decimal(p + "movement-speed", 0.0, 10.0),
            in.decimal(p + "armor", 0.0, 30.0),
            in.decimal(p + "armor-toughness", 0.0, 20.0),
            in.decimal(p + "knockback-resistance", 0.0, 1.0),
            in.decimal(p + "follow-range", 1.0, 128.0),
            in.bool(p + "assist-activation"),
            in.integer(p + "check-interval-ticks", 1),
            in.damageCauses(p + "immune-damage-causes"),
            arena,
            readPhases(in, p + "phases"),
            hearts,
            shockwave,
            resin,
            in.sfx(p + "spawn-sound"),
            in.fx(p + "spawn-particles", true),
            in.sfx(p + "death-sound"),
            in.sfx(p + "hurt-sound"),
            in.sfx(p + "shield-start-sound"),
            in.sfx(p + "shield-end-sound"),
            in.sfx(p + "shield-blocked-sound"),
            in.fx(p + "shield-aura-particles", true),
            in.integer(p + "shield-aura-particles.interval-ticks", 1),
            rewards(in, p + "rewards."),
            messages);
    }

    /** Phases d'invulnérabilité, de la plus haute à la plus basse part de PV. Une entrée invalide est ignorée. */
    private List<CreakingSettings.Phase> readPhases(ConfigReader in, String path) {
        List<CreakingSettings.Phase> phases = new ArrayList<>();
        List<Map<?, ?>> raw = plugin.getConfig().getMapList(path);
        int index = 0;
        for (Map<?, ?> entry : raw) {
            index++;
            Object percent = entry.get("health-percent");
            Object hearts = entry.get("hearts");
            if (!(percent instanceof Number pct) || pct.doubleValue() <= 0.0 || pct.doubleValue() >= 100.0
                || !(hearts instanceof Number count) || count.intValue() < 1) {
                in.warn("Phase n°" + index + " de '" + path + "' ignorée : 'health-percent' (entre 0 et 100 exclus) et 'hearts' (au moins 1) sont obligatoires.");
                continue;
            }
            phases.add(new CreakingSettings.Phase(pct.doubleValue(), count.intValue()));
        }
        phases.sort(Comparator.comparingDouble(CreakingSettings.Phase::healthPercent).reversed());
        return List.copyOf(phases);
    }

    private List<CreakingSettings.Effect> readEffects(ConfigReader in, String path) {
        List<CreakingSettings.Effect> effects = new ArrayList<>();
        for (Map<?, ?> entry : plugin.getConfig().getMapList(path)) {
            Object type = entry.get("type");
            PotionEffectType effect = in.potionEffect(type == null ? null : String.valueOf(type), path);
            if (effect == null) continue;
            Object amplifier = entry.get("amplifier");
            Object duration = entry.get("duration-ticks");
            int level = amplifier instanceof Number n && n.intValue() >= 0 ? n.intValue() : 0;
            int ticks = duration instanceof Number n && n.intValue() > 0 ? n.intValue() : 0;
            if (ticks == 0) {
                in.warn("Effet '" + type + "' de '" + path + "' ignoré : 'duration-ticks' doit être supérieur à 0.");
                continue;
            }
            effects.add(new CreakingSettings.Effect(effect, level, ticks));
        }
        return List.copyOf(effects);
    }

    // ------------------------------------------------------------------ éléments communs aux boss

    private static DynamicStats dynamicStats(ConfigReader in, String p) {
        return new DynamicStats(
            in.bool(p + "dynamic-stats.enabled"),
            in.decimal(p + "dynamic-stats.health-per-island-level", 0.0),
            in.decimal(p + "dynamic-stats.health-per-island-member", 0.0));
    }

    private Rewards rewards(ConfigReader in, String p) {
        List<Rewards.ItemReward> items = new ArrayList<>();
        for (Map<?, ?> entry : plugin.getConfig().getMapList(p + "items")) {
            Object materialName = entry.get("material");
            Material material = materialName == null ? null : Material.matchMaterial(String.valueOf(materialName).trim());
            if (material == null || material.isLegacy() || material.isAir() || !material.isItem()) {
                in.warn("Objet de récompense ignoré dans '" + p + "items' : matériau invalide '" + materialName + "'.");
                continue;
            }
            int amount = entry.get("amount") instanceof Number n && n.intValue() > 0 ? n.intValue() : 1;
            String name = entry.get("name") == null ? null : ChatColor.translateAlternateColorCodes('&', String.valueOf(entry.get("name")));
            List<String> lore = new ArrayList<>();
            if (entry.get("lore") instanceof List<?> lines) {
                for (Object line : lines) lore.add(ChatColor.translateAlternateColorCodes('&', String.valueOf(line)));
            }
            items.add(new Rewards.ItemReward(material, amount, name, List.copyOf(lore)));
        }
        return new Rewards(
            in.integer(p + "xp-reward", 0),
            List.copyOf(items),
            List.copyOf(plugin.getConfig().getStringList(p + "commands")));
    }

    // ------------------------------------------------------------------ accès

    public CommonMessages common() {
        return common;
    }

    /** Réglages du rituel du boss {@code type}, ou null si ce rituel est inutilisable. */
    public RitualSettings ritual(BossType type) {
        return rituals.get(type);
    }

    /** Rituel activé dont le bloc est {@code block}, ou null. */
    public RitualSettings ritualForBlock(Material block) {
        return ritualsByBlock.get(block);
    }

    public DragonSettings dragon() {
        return dragon;
    }

    public CreakingSettings creaking() {
        return creaking;
    }

    public BossSettings bossSettings(BossType type) {
        return switch (type) {
            case DRAGON -> dragon;
            case CREAKING -> creaking;
        };
    }

    public String getIslandPermissionName() {
        return islandPermissionName;
    }
}

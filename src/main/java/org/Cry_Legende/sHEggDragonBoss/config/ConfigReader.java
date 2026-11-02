package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Lecture validée de config.yml.
 * <p>
 * Aucune valeur de jeu n'est écrite dans le code : une clé absente ou invalide reprend la valeur du config.yml
 * livré dans le jar (Bukkit le charge comme défauts), avec un avertissement clair dans la console.
 * Si même cette valeur livrée est inutilisable, la méthode renvoie une valeur neutre (null pour un matériau, un son
 * ou une particule, qui désactive alors la fonction) : jamais d'exception ni de NPE à cause de la configuration.
 */
public final class ConfigReader {

    private final FileConfiguration config;
    private final Logger log;
    private int warnings;

    public ConfigReader(FileConfiguration config, Logger log) {
        this.config = config;
        this.log = log;
    }

    public int warnings() {
        return warnings;
    }

    public void warn(String message) {
        warnings++;
        log.warning("[config.yml] " + message);
    }

    // ------------------------------------------------------------------ valeurs brutes

    /** Valeur écrite par l'administrateur (sans les défauts livrés), ou null. */
    private Object user(String path) {
        return config.contains(path, true) ? config.get(path) : null;
    }

    /** Valeur du config.yml livré dans le jar, ou null. */
    private Object shipped(String path) {
        Configuration defaults = config.getDefaults();
        return defaults == null ? null : defaults.get(path);
    }

    /** Valeur de l'administrateur, sinon valeur livrée (avec avertissement : la clé manque dans son fichier). */
    private Object valueOrShipped(String path) {
        Object value = user(path);
        if (value != null) return value;
        Object fallback = shipped(path);
        if (fallback != null) {
            warn("Clé absente : '" + path + "'. La valeur livrée est utilisée : " + describe(fallback) + ".");
        } else {
            warn("Clé inconnue : '" + path + "'.");
        }
        return fallback;
    }

    private static String describe(Object value) {
        return value instanceof List<?> list ? list.size() + " élément(s)" : "'" + value + "'";
    }

    public boolean has(String path) {
        return config.contains(path, true);
    }

    // ------------------------------------------------------------------ nombres, booléens, textes

    public int integer(String path, int min) {
        Object value = valueOrShipped(path);
        Integer parsed = asInt(value);
        if (parsed != null && parsed >= min) return parsed;
        Integer fallback = asInt(shipped(path));
        int result = fallback != null && fallback >= min ? fallback : min;
        if (value != null) warn("Valeur invalide pour '" + path + "' : '" + value + "' (entier >= " + min + " attendu). Utilisation de " + result + ".");
        return result;
    }

    public double decimal(String path, double min, double max) {
        Object value = valueOrShipped(path);
        Double parsed = asDouble(value);
        if (parsed != null && parsed >= min && parsed <= max) return parsed;
        Double fallback = asDouble(shipped(path));
        double result = fallback != null && fallback >= min && fallback <= max ? fallback : min;
        if (value != null) warn("Valeur invalide pour '" + path + "' : '" + value + "' (nombre entre " + min + " et " + max + " attendu). Utilisation de " + result + ".");
        return result;
    }

    public double decimal(String path, double min) {
        return decimal(path, min, Double.MAX_VALUE);
    }

    public boolean bool(String path) {
        Object value = valueOrShipped(path);
        Boolean parsed = asBoolean(value);
        if (parsed != null) return parsed;
        Boolean fallback = asBoolean(shipped(path));
        boolean result = fallback != null && fallback;
        if (value != null) warn("Valeur invalide pour '" + path + "' : '" + value + "' (true ou false attendu). Utilisation de " + result + ".");
        return result;
    }

    /** Texte avec codes couleur « & » traduits. Vide autorisé (= pas de message). */
    public String text(String path) {
        Object value = valueOrShipped(path);
        if (value == null) return "";
        return ChatColor.translateAlternateColorCodes('&', String.valueOf(value));
    }

    /** Texte brut, sans traduction des couleurs (identifiants). */
    public String rawText(String path) {
        Object value = valueOrShipped(path);
        return value == null ? "" : String.valueOf(value).trim();
    }

    public List<String> textList(String path) {
        Object value = user(path);
        if (!(value instanceof List<?>)) {
            if (value != null) warn("Valeur invalide pour '" + path + "' : une liste est attendue. La liste livrée est utilisée.");
            else if (shipped(path) != null) warn("Clé absente : '" + path + "'. La liste livrée est utilisée.");
            value = shipped(path);
        }
        List<String> lines = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object line : list) lines.add(ChatColor.translateAlternateColorCodes('&', String.valueOf(line)));
        }
        return lines;
    }

    // ------------------------------------------------------------------ matériaux, énumérations, sons, particules

    public Material block(String path) {
        return material(path, true);
    }

    public Material item(String path) {
        return material(path, false);
    }

    private Material material(String path, boolean mustBeBlock) {
        Object value = valueOrShipped(path);
        Material parsed = parseMaterial(value, mustBeBlock);
        if (parsed != null) return parsed;
        Material fallback = parseMaterial(shipped(path), mustBeBlock);
        if (value != null) {
            warn("Matériau invalide pour '" + path + "' : '" + value + "' (" + (mustBeBlock ? "un bloc" : "un objet") + " attendu)."
                + (fallback != null ? " Utilisation de " + fallback + "." : " Fonction désactivée."));
        }
        return fallback;
    }

    private static Material parseMaterial(Object value, boolean mustBeBlock) {
        if (value == null) return null;
        Material material = Material.matchMaterial(String.valueOf(value).trim());
        if (material == null || material.isLegacy() || material.isAir()) return null;
        if (mustBeBlock ? !material.isBlock() : !material.isItem()) return null;
        return material;
    }

    /** Valeur d'une énumération Bukkit (BarColor, BarStyle...). Renvoie le défaut livré si la valeur est inconnue. */
    public <E extends Enum<E>> E enumValue(String path, Class<E> type) {
        Object value = valueOrShipped(path);
        E parsed = parseEnum(value, type);
        if (parsed != null) return parsed;
        E fallback = parseEnum(shipped(path), type);
        if (value != null) {
            warn("Valeur invalide pour '" + path + "' : '" + value + "'. Valeurs possibles : " + List.of(type.getEnumConstants())
                + (fallback != null ? ". Utilisation de " + fallback + "." : "."));
        }
        return fallback != null ? fallback : type.getEnumConstants()[0];
    }

    private static <E extends Enum<E>> E parseEnum(Object value, Class<E> type) {
        if (value == null) return null;
        try {
            return Enum.valueOf(type, String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Liste de causes de dégâts ; une cause inconnue est ignorée avec un avertissement. */
    public Set<EntityDamageEvent.DamageCause> damageCauses(String path) {
        Object value = user(path);
        if (!(value instanceof List<?>)) {
            if (value != null) warn("Valeur invalide pour '" + path + "' : une liste est attendue. La liste livrée est utilisée.");
            else warn("Clé absente : '" + path + "'. La liste livrée est utilisée.");
            value = shipped(path);
        }
        Set<EntityDamageEvent.DamageCause> causes = EnumSet.noneOf(EntityDamageEvent.DamageCause.class);
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                EntityDamageEvent.DamageCause cause = parseEnum(entry, EntityDamageEvent.DamageCause.class);
                if (cause == null) warn("Cause de dégâts inconnue dans '" + path + "' : '" + entry + "'. Ignorée.");
                else causes.add(cause);
            }
        }
        return causes;
    }

    /** Type d'entité vivante invocable ; sinon le défaut livré. */
    public EntityType livingEntityType(String path) {
        Object value = valueOrShipped(path);
        EntityType parsed = parseLivingEntity(value);
        if (parsed != null) return parsed;
        EntityType fallback = parseLivingEntity(shipped(path));
        if (value != null) {
            warn("Type d'entité invalide pour '" + path + "' : '" + value + "' (une créature invocable est attendue)."
                + (fallback != null ? " Utilisation de " + fallback + "." : ""));
        }
        return fallback != null ? fallback : EntityType.PHANTOM;
    }

    private static EntityType parseLivingEntity(Object value) {
        EntityType type = parseEnum(value, EntityType.class);
        if (type == null || !type.isSpawnable() || type.getEntityClass() == null) return null;
        return LivingEntity.class.isAssignableFrom(type.getEntityClass()) ? type : null;
    }

    public PotionEffectType potionEffect(String raw, String path) {
        if (raw != null && !raw.isBlank()) {
            NamespacedKey key = NamespacedKey.fromString(raw.trim().toLowerCase(Locale.ROOT));
            PotionEffectType type = key == null ? null : Registry.MOB_EFFECT.get(key);
            if (type != null) return type;
        }
        warn("Effet de potion inconnu dans '" + path + "' : '" + raw + "'. Effet ignoré.");
        return null;
    }

    public Sound sound(String path) {
        Object value = valueOrShipped(path);
        if (value == null || String.valueOf(value).isBlank()) return null;
        Sound parsed = parseSound(String.valueOf(value));
        if (parsed != null) return parsed;
        Sound fallback = shipped(path) == null ? null : parseSound(String.valueOf(shipped(path)));
        warn("Son inconnu pour '" + path + "' : '" + value + "'." + (fallback != null ? " Utilisation de " + fallback.key().asString() + "." : " Son désactivé."));
        return fallback;
    }

    /** Son lu en config : nom Bukkit (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup). */
    private static Sound parseSound(String raw) {
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

    /** Son réglable : {@code path.sound}, {@code path.volume}, {@code path.pitch}. Un nom vide désactive le son. */
    public Sfx sfx(String path) {
        Sound sound = sound(path + ".sound");
        float volume = (float) decimal(path + ".volume", 0.0, 100.0);
        float pitch = (float) decimal(path + ".pitch", 0.0, 10.0);
        return sound == null ? Sfx.SILENT : new Sfx(sound, volume, pitch);
    }

    public Particle particle(String path) {
        Object value = valueOrShipped(path);
        if (value == null || String.valueOf(value).isBlank()) return null;
        Particle parsed = parseParticle(value);
        if (parsed != null) return parsed;
        Particle fallback = parseParticle(shipped(path));
        warn("Particule invalide pour '" + path + "' : '" + value + "' (une particule sans données est attendue, par exemple CLOUD)."
            + (fallback != null ? " Utilisation de " + fallback + "." : " Particule désactivée."));
        return fallback;
    }

    /** Les particules qui exigent des données (BLOCK, DUST, ITEM...) ne peuvent pas être jouées depuis la config. */
    private static Particle parseParticle(Object value) {
        if (value == null) return null;
        Particle particle = parseEnum(value, Particle.class);
        return particle != null && particle.getDataType() == Void.class ? particle : null;
    }

    /** Effet de particules : {@code path.particle}, {@code path.count}, {@code path.speed} et, si demandé, {@code path.spread}. */
    public Fx fx(String path, boolean withSpread) {
        Particle particle = particle(path + ".particle");
        int count = integer(path + ".count", 0);
        double spread = withSpread ? decimal(path + ".spread", 0.0) : 0.0;
        double speed = decimal(path + ".speed", 0.0);
        return particle == null ? Fx.NONE : new Fx(particle, count, spread, speed);
    }

    // ------------------------------------------------------------------ conversions

    private static Integer asInt(Object value) {
        if (!(value instanceof Number number)) return null;
        double d = number.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) return null;
        if (d > Integer.MAX_VALUE || d < Integer.MIN_VALUE) return null;
        return (int) d;
    }

    private static Double asDouble(Object value) {
        if (!(value instanceof Number number)) return null;
        double d = number.doubleValue();
        return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
    }

    private static Boolean asBoolean(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) {
            if (s.equalsIgnoreCase("true")) return Boolean.TRUE;
            if (s.equalsIgnoreCase("false")) return Boolean.FALSE;
        }
        return null;
    }
}

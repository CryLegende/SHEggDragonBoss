package org.Cry_Legende.sHEggDragonBoss.config;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tous les textes du plugin, lus dans messages.yml. Une clé absente du fichier de l'administrateur reprend le texte livré
 * dans le jar, avec un avertissement dans la console (jamais d'exception). Les couleurs s'écrivent {@code &} ou {@code &#RRGGBB} ;
 * les placeholders s'écrivent {@code %nom%}.
 */
public final class Messages {

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    private final JavaPlugin plugin;
    private final File file;
    private YamlConfiguration config = new YamlConfiguration();
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
    }

    /** Lit messages.yml (copié depuis le jar s'il manque). Peut être rappelé à chaud. */
    public void load() {
        if (!file.exists()) plugin.saveResource("messages.yml", false);
        warned.clear();
        YamlConfiguration loaded = new YamlConfiguration();
        try {
            loaded.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("messages.yml illisible (" + e.getMessage() + ") : les textes livrés avec le plugin sont utilisés.");
            warned.add("<fichier>");
            loaded = new YamlConfiguration();
        }
        try (InputStream in = plugin.getResource("messages.yml")) {
            if (in != null) loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            plugin.getLogger().warning("messages.yml livré dans le jar illisible : " + e.getMessage());
        }
        config = loaded;
    }

    /** Nombre d'avertissements (clés absentes, fichier illisible) depuis le dernier chargement. */
    public int warnings() {
        return warned.size();
    }

    // ------------------------------------------------------------------ lecture

    private Object value(String path) {
        if (config.contains(path, true)) return config.get(path);
        Configuration defaults = config.getDefaults();
        Object fallback = defaults == null ? null : defaults.get(path);
        if (warned.add(path)) {
            if (fallback != null) plugin.getLogger().warning("[messages.yml] Clé absente : '" + path + "'. Le texte livré est utilisé.");
            else plugin.getLogger().warning("[messages.yml] Clé inconnue : '" + path + "'.");
        }
        return fallback;
    }

    /** Texte brut, sans traduction des couleurs. */
    public String raw(String path) {
        Object value = value(path);
        return value == null ? "" : String.valueOf(value);
    }

    /** Texte avec couleurs. Vide autorisé (= rien à envoyer). */
    public String text(String path) {
        return color(raw(path));
    }

    /** Texte avec couleurs et placeholders {@code %nom%} remplacés ; {@code pairs} = nom, valeur, nom, valeur... */
    public String format(String path, String... pairs) {
        return apply(text(path), pairs);
    }

    /** Liste de lignes avec couleurs (une chaîne simple donne une seule ligne). */
    public List<String> lines(String path) {
        Object value = value(path);
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object line : list) result.add(color(String.valueOf(line)));
        } else if (value != null) {
            result.add(color(String.valueOf(value)));
        }
        return result;
    }

    /** Ligne du journal des boss (journal.&lt;clé&gt;), sans couleurs. */
    public String journal(String key, String... pairs) {
        return ChatColor.stripColor(apply(color(raw("journal." + key)), pairs));
    }

    // ------------------------------------------------------------------ envoi et mise en forme

    /** Envoie le message ; un message vide n'est pas envoyé. */
    public void send(CommandSender to, String path, String... pairs) {
        String message = format(path, pairs);
        if (!message.isEmpty()) to.sendMessage(message);
    }

    /** Remplace {@code %nom%} par la valeur pour chaque couple nom, valeur. */
    public static String apply(String text, String... pairs) {
        String result = text;
        for (int i = 0; i + 1 < pairs.length; i += 2) result = result.replace("%" + pairs[i] + "%", pairs[i + 1]);
        return result;
    }

    /** Traduit {@code &#RRGGBB} puis les codes {@code &} classiques. */
    public static String color(String text) {
        Matcher matcher = HEX.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            StringBuilder hex = new StringBuilder("§x");
            for (char c : matcher.group(1).toCharArray()) hex.append('§').append(c);
            matcher.appendReplacement(out, Matcher.quoteReplacement(hex.toString()));
        }
        matcher.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }
}

package org.Cry_Legende.sHEggDragonBoss.managers;

import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.boss.BossType;
import org.Cry_Legende.sHEggDragonBoss.config.RitualSettings;
import org.Cry_Legende.sHEggDragonBoss.ritual.RitualProcess;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Données persistantes : rituels en cours (hatching-data.yml) et boss dormants (dormant-bosses.yml).
 * Le champ {@code type} (dragon, creaking) est facultatif : son absence signifie « dragon », comme dans les fichiers
 * écrits avant l'arrivée du second boss.
 */
public class BossDataManager {

    /** Un boss dormant ; {@code phase} et {@code heartsLeft} ne servent qu'au Creaking (0 pour le dragon). */
    public record BossData(UUID islandId, BossType type, Location lastLocation, double currentHealth, double maxHealth, int phase, int heartsLeft) {
    }

    // Entrées dont le monde n'est pas encore chargé (PhantomWorlds en charge après onEnable) ou dont le rituel est désactivé :
    // gardées telles quelles, réécrites à chaque sauvegarde, et activées au chargement du monde (WorldLoadEvent)
    private record PendingRitual(String type, String location, int items) {
    }

    private record PendingDormant(UUID islandId, String type, String location, double currentHealth, double maxHealth, int phase, int heartsLeft) {
    }

    private final SHEggDragonBoss plugin;
    private final File hatchingDataFile;
    private final File dormantBossesFile;
    private final List<PendingRitual> pendingRituals = new ArrayList<>();
    private final List<PendingDormant> pendingDormant = new ArrayList<>();

    public BossDataManager(SHEggDragonBoss plugin) {
        this.plugin = plugin;
        this.hatchingDataFile = new File(plugin.getDataFolder(), "hatching-data.yml");
        this.dormantBossesFile = new File(plugin.getDataFolder(), "dormant-bosses.yml");
    }

    public Map<Location, RitualProcess> loadRitualData() {
        Map<Location, RitualProcess> processes = new ConcurrentHashMap<>();
        if (!hatchingDataFile.exists()) return processes;
        FileConfiguration config = YamlConfiguration.loadConfiguration(hatchingDataFile);
        ConfigurationSection section = config.getConfigurationSection("hatching-processes");
        if (section == null) return processes;
        for (String key : section.getKeys(false)) {
            String raw = section.getString(key + ".location");
            String type = section.getString(key + ".type", BossType.DRAGON.id());
            int items = section.getInt(key + ".shards");
            Location loc = deserializeLocation(raw);
            if (loc == null) continue;
            if (loc.getWorld() == null) {
                pendingRituals.add(new PendingRitual(type, raw, items));
                continue;
            }
            if (!addRitual(processes, type, loc, items)) pendingRituals.add(new PendingRitual(type, raw, items));
        }
        return processes;
    }

    /**
     * Recrée un rituel sauvegardé. Renvoie faux quand l'entrée doit rester en attente (type inconnu ou rituel désactivé) ;
     * un bloc qui n'est plus là est simplement abandonné, comme avant.
     */
    private boolean addRitual(Map<Location, RitualProcess> processes, String typeId, Location loc, int items) {
        BossType type = BossType.fromId(typeId);
        RitualSettings ritual = type == null ? null : plugin.getConfigManager().ritual(type);
        if (ritual == null || !ritual.enabled()) {
            plugin.getLogger().warning("Rituel '" + typeId + "' en " + serializeLocation(loc) + " conservé tel quel : ce rituel est inconnu ou désactivé.");
            return false;
        }
        if (loc.getBlock().getType() != ritual.block()) {
            // Arrêt brutal en plein compte à rebours : l'animation avait retiré le bloc du monde. On le remet (les objets
            // donnés sont dans les données) : un clic avec l'objet relance le compte à rebours au lieu de tout perdre.
            if (loc.getBlock().getType().isAir() && items >= ritual.requiredAmount()) loc.getBlock().setType(ritual.block());
            else return true;
        }
        RitualProcess process = new RitualProcess(plugin, ritual, loc);
        process.setCurrentItems(items);
        process.updateHologram();
        processes.put(loc, process);
        return true;
    }

    /** Active les rituels en attente d'un monde qui vient de se charger. */
    public Map<Location, RitualProcess> resolveRituals(World world) {
        Map<Location, RitualProcess> processes = new ConcurrentHashMap<>();
        List<PendingRitual> kept = new ArrayList<>();
        Iterator<PendingRitual> it = pendingRituals.iterator();
        while (it.hasNext()) {
            PendingRitual pending = it.next();
            if (!worldName(pending.location()).equalsIgnoreCase(world.getName())) continue;
            it.remove();
            Location loc = deserializeLocation(pending.location());
            if (loc != null && loc.getWorld() != null && !addRitual(processes, pending.type(), loc, pending.items())) kept.add(pending);
        }
        pendingRituals.addAll(kept);
        return processes;
    }

    public void saveRitualData(Map<Location, RitualProcess> processes) {
        FileConfiguration config = new YamlConfiguration();
        int i = 0;
        for (Map.Entry<Location, RitualProcess> entry : processes.entrySet()) {
            String path = "hatching-processes." + i++;
            config.set(path + ".type", entry.getValue().getRitual().type().id());
            config.set(path + ".location", serializeLocation(entry.getKey()));
            config.set(path + ".shards", entry.getValue().getCurrentItems());
        }
        for (PendingRitual pending : pendingRituals) {
            String path = "hatching-processes." + i++;
            config.set(path + ".type", pending.type());
            config.set(path + ".location", pending.location());
            config.set(path + ".shards", pending.items());
        }
        try {
            config.save(hatchingDataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder les données d'éclosion !");
        }
    }

    public Map<UUID, BossData> loadDormantBosses() {
        Map<UUID, BossData> bosses = new ConcurrentHashMap<>();
        if (!dormantBossesFile.exists()) return bosses;
        FileConfiguration config = YamlConfiguration.loadConfiguration(dormantBossesFile);
        ConfigurationSection section = config.getConfigurationSection("dormant-bosses");
        if (section == null) return bosses;
        for (String key : section.getKeys(false)) {
            try {
                UUID islandId = UUID.fromString(key);
                String typeId = section.getString(key + ".type", BossType.DRAGON.id());
                BossType type = BossType.fromId(typeId);
                if (type == null) {
                    plugin.getLogger().warning("Boss dormant ignoré (île " + key + ") : type inconnu '" + typeId + "'.");
                    continue;
                }
                String raw = section.getString(key + ".location");
                Location loc = deserializeLocation(raw);
                if (loc == null) continue;
                double currentHealth = section.getDouble(key + ".current-health");
                double maxHealth = section.getDouble(key + ".max-health");
                int phase = section.getInt(key + ".phase");
                int heartsLeft = section.getInt(key + ".hearts-left");
                if (loc.getWorld() == null) {
                    pendingDormant.add(new PendingDormant(islandId, type.id(), raw, currentHealth, maxHealth, phase, heartsLeft));
                    continue;
                }
                bosses.put(islandId, new BossData(islandId, type, loc, currentHealth, maxHealth, phase, heartsLeft));
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur au chargement d'un boss dormant: " + e.getMessage());
            }
        }
        plugin.getLogger().info((bosses.size() + pendingDormant.size()) + " boss dormants ont été chargés.");
        return bosses;
    }

    /** Active les boss dormants en attente d'un monde qui vient de se charger. */
    public Map<UUID, BossData> resolveDormant(World world) {
        Map<UUID, BossData> bosses = new ConcurrentHashMap<>();
        Iterator<PendingDormant> it = pendingDormant.iterator();
        while (it.hasNext()) {
            PendingDormant pending = it.next();
            if (!worldName(pending.location()).equalsIgnoreCase(world.getName())) continue;
            it.remove();
            Location loc = deserializeLocation(pending.location());
            BossType type = BossType.fromId(pending.type());
            if (loc != null && loc.getWorld() != null && type != null) {
                bosses.put(pending.islandId(), new BossData(pending.islandId(), type, loc, pending.currentHealth(), pending.maxHealth(), pending.phase(), pending.heartsLeft()));
            }
        }
        return bosses;
    }

    public void saveDormantBosses(Map<UUID, BossData> bosses) {
        FileConfiguration config = new YamlConfiguration();
        if (bosses != null) {
            for (Map.Entry<UUID, BossData> entry : bosses.entrySet()) {
                BossData data = entry.getValue();
                String location = serializeLocation(data.lastLocation());
                if (location.isEmpty()) continue; // position inconnue : rien d'exploitable à écrire
                writeDormant(config, entry.getKey(), data.type().id(), location, data.currentHealth(), data.maxHealth(), data.phase(), data.heartsLeft());
            }
        }
        for (PendingDormant pending : pendingDormant) {
            writeDormant(config, pending.islandId(), pending.type(), pending.location(), pending.currentHealth(), pending.maxHealth(), pending.phase(), pending.heartsLeft());
        }
        try {
            config.save(dormantBossesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder les données des boss dormants !");
        }
    }

    private static void writeDormant(FileConfiguration config, UUID islandId, String type, String location, double currentHealth, double maxHealth, int phase, int heartsLeft) {
        String path = "dormant-bosses." + islandId;
        config.set(path + ".type", type);
        config.set(path + ".location", location);
        config.set(path + ".current-health", currentHealth);
        config.set(path + ".max-health", maxHealth);
        // Phase et cœurs restants : seulement pour le boss qui en a
        if (phase > 0 || heartsLeft > 0) {
            config.set(path + ".phase", phase);
            config.set(path + ".hearts-left", heartsLeft);
        }
    }

    private static String worldName(String raw) {
        int sep = raw.indexOf(';');
        return sep < 0 ? raw : raw.substring(0, sep);
    }

    private String serializeLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return "";
        return loc.getWorld().getName() + ";" + loc.getX() + ";" + loc.getY() + ";" + loc.getZ() + ";" + loc.getYaw() + ";" + loc.getPitch();
    }

    /** Le monde du résultat est null s'il n'est pas (encore) chargé ; le résultat est null si la chaîne est illisible. */
    private Location deserializeLocation(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] parts = s.split(";");
            World w = Bukkit.getWorld(parts[0]);
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = Float.parseFloat(parts[4]);
            float pitch = Float.parseFloat(parts[5]);
            return new Location(w, x, y, z, yaw, pitch);
        } catch (Exception e) {
            return null;
        }
    }
}

package org.Cry_Legende.sHEggDragonBoss.managers;

import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.hatching.HatchingProcess; // IMPORT AJOUTÉ
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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

public class BossDataManager {

    public record BossData(UUID islandId, Location lastLocation, double currentHealth, double maxHealth) {
    }

    // Entrées dont le monde n'est pas encore chargé (PhantomWorlds en charge après onEnable) : gardées telles quelles,
    // réécrites à chaque sauvegarde, et activées au chargement du monde (WorldLoadEvent)
    private record PendingHatching(String location, int shards) {
    }

    private record PendingDormant(UUID islandId, String location, double currentHealth, double maxHealth) {
    }

    private final SHEggDragonBoss plugin;
    private final File hatchingDataFile;
    private final File dormantBossesFile;
    private final List<PendingHatching> pendingHatching = new ArrayList<>();
    private final List<PendingDormant> pendingDormant = new ArrayList<>();

    public BossDataManager(SHEggDragonBoss plugin) {
        this.plugin = plugin;
        this.hatchingDataFile = new File(plugin.getDataFolder(), "hatching-data.yml");
        this.dormantBossesFile = new File(plugin.getDataFolder(), "dormant-bosses.yml");
    }

    public Map<Location, HatchingProcess> loadHatchingData() {
        Map<Location, HatchingProcess> processes = new ConcurrentHashMap<>();
        if (!hatchingDataFile.exists()) return processes;
        FileConfiguration config = YamlConfiguration.loadConfiguration(hatchingDataFile);
        ConfigurationSection section = config.getConfigurationSection("hatching-processes");
        if (section == null) return processes;
        for (String key : section.getKeys(false)) {
            String raw = section.getString(key + ".location");
            int shards = section.getInt(key + ".shards");
            Location loc = deserializeLocation(raw);
            if (loc == null) continue;
            if (loc.getWorld() == null) {
                pendingHatching.add(new PendingHatching(raw, shards));
                continue;
            }
            addHatching(processes, loc, shards);
        }
        return processes;
    }

    private void addHatching(Map<Location, HatchingProcess> processes, Location loc, int shards) {
        if (loc.getBlock().getType() != Material.DRAGON_EGG) return;
        HatchingProcess process = new HatchingProcess(plugin, loc);
        process.setCurrentShards(shards);
        process.updateHologram(plugin.getConfigManager());
        processes.put(loc, process);
    }

    /** Active les éclosions en attente d'un monde qui vient de se charger. */
    public Map<Location, HatchingProcess> resolveHatching(World world) {
        Map<Location, HatchingProcess> processes = new ConcurrentHashMap<>();
        Iterator<PendingHatching> it = pendingHatching.iterator();
        while (it.hasNext()) {
            PendingHatching pending = it.next();
            if (!worldName(pending.location()).equalsIgnoreCase(world.getName())) continue;
            it.remove();
            Location loc = deserializeLocation(pending.location());
            if (loc != null && loc.getWorld() != null) addHatching(processes, loc, pending.shards());
        }
        return processes;
    }

    public void saveHatchingData(Map<Location, HatchingProcess> processes) {
        FileConfiguration config = new YamlConfiguration();
        int i = 0;
        for (Map.Entry<Location, HatchingProcess> entry : processes.entrySet()) {
            String path = "hatching-processes." + i++;
            config.set(path + ".location", serializeLocation(entry.getKey()));
            config.set(path + ".shards", entry.getValue().getCurrentShards());
        }
        for (PendingHatching pending : pendingHatching) {
            String path = "hatching-processes." + i++;
            config.set(path + ".location", pending.location());
            config.set(path + ".shards", pending.shards());
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
                String raw = section.getString(key + ".location");
                Location loc = deserializeLocation(raw);
                if (loc == null) continue;
                double currentHealth = section.getDouble(key + ".current-health");
                double maxHealth = section.getDouble(key + ".max-health");
                if (loc.getWorld() == null) {
                    pendingDormant.add(new PendingDormant(islandId, raw, currentHealth, maxHealth));
                    continue;
                }
                bosses.put(islandId, new BossData(islandId, loc, currentHealth, maxHealth));
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
            if (loc != null && loc.getWorld() != null) {
                bosses.put(pending.islandId(), new BossData(pending.islandId(), loc, pending.currentHealth(), pending.maxHealth()));
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
                String path = "dormant-bosses." + entry.getKey().toString();
                config.set(path + ".location", location);
                config.set(path + ".current-health", data.currentHealth());
                config.set(path + ".max-health", data.maxHealth());
            }
        }
        for (PendingDormant pending : pendingDormant) {
            String path = "dormant-bosses." + pending.islandId();
            config.set(path + ".location", pending.location());
            config.set(path + ".current-health", pending.currentHealth());
            config.set(path + ".max-health", pending.maxHealth());
        }
        try {
            config.save(dormantBossesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder les données des boss dormants !");
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

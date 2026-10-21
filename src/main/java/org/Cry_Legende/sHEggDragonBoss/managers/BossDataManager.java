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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BossDataManager {

    public record BossData(UUID islandId, Location lastLocation, double currentHealth, double maxHealth) {
    }

    private final SHEggDragonBoss plugin;
    private final File hatchingDataFile;
    private final File dormantBossesFile;

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
            Location loc = deserializeLocation(section.getString(key + ".location"));
            if (loc == null || loc.getBlock().getType() != Material.DRAGON_EGG) continue;
            HatchingProcess process = new HatchingProcess(plugin, loc);
            process.setCurrentShards(section.getInt(key + ".shards"));
            process.updateHologram(plugin.getConfigManager());
            processes.put(loc, process);
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
                Location loc = deserializeLocation(section.getString(key + ".location"));
                if (loc == null) continue;
                double currentHealth = section.getDouble(key + ".current-health");
                double maxHealth = section.getDouble(key + ".max-health");
                bosses.put(islandId, new BossData(islandId, loc, currentHealth, maxHealth));
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur au chargement d'un boss dormant: " + e.getMessage());
            }
        }
        plugin.getLogger().info(bosses.size() + " boss dormants ont été chargés.");
        return bosses;
    }

    public void saveDormantBosses(Map<UUID, BossData> bosses) {
        FileConfiguration config = new YamlConfiguration();
        if (bosses != null && !bosses.isEmpty()) {
            for (Map.Entry<UUID, BossData> entry : bosses.entrySet()) {
                String path = "dormant-bosses." + entry.getKey().toString();
                BossData data = entry.getValue();
                config.set(path + ".location", serializeLocation(data.lastLocation()));
                config.set(path + ".current-health", data.currentHealth());
                config.set(path + ".max-health", data.maxHealth());
            }
        }
        try {
            config.save(dormantBossesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Impossible de sauvegarder les données des boss dormants !");
        }
    }

    private String serializeLocation(Location loc) {
        if (loc == null) return "";
        return loc.getWorld().getName() + ";" + loc.getX() + ";" + loc.getY() + ";" + loc.getZ() + ";" + loc.getYaw() + ";" + loc.getPitch();
    }

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

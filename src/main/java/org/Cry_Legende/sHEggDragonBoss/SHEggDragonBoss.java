package org.Cry_Legende.sHEggDragonBoss;

import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import org.Cry_Legende.sHEggDragonBoss.listeners.PlayerListener;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.ConfigManager;
import org.Cry_Legende.sHEggDragonBoss.managers.LogManager;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SHEggDragonBoss extends JavaPlugin {

    private static SHEggDragonBoss instance;
    private ConfigManager configManager;
    private BossManager bossManager;
    private FancyHologramsPlugin fancyHologramsPlugin;
    private LogManager logManager;

    public static NamespacedKey CHUNK_LOAD_KEY;
    public static NamespacedKey BOSS_KEY;

    /**
     * CORRECTION : Utilisation de onLoad() pour enregistrer la permission.
     * Cette méthode s'exécute AVANT le onEnable() de SuperiorSkyblock2 (grâce au 'depend' dans plugin.yml).
     * Cela garantit que la permission existe dans le registre avant que SSB2 ne charge les données des îles.
     */
    @Override
    public void onLoad() {
        instance = this; // On initialise l'instance tôt pour le ConfigManager

        // 1. On doit charger la configuration MAINTENANT pour connaître le nom de la permission
        this.configManager = new ConfigManager(this);
        configManager.loadConfig();

        // 2. On enregistre la permission immédiatement
        registerIslandPermission();
    }

    @Override
    public void onEnable() {
        // Note: 'instance' et 'configManager' sont déjà initialisés dans onLoad().

        CHUNK_LOAD_KEY = new NamespacedKey(this, "forced_chunk_load");
        BOSS_KEY = new NamespacedKey(this, "shegg_boss");

        if (!setupDependencies()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.logManager = new LogManager(this);

        // configManager.loadConfig(); <-- Déjà fait dans onLoad(), pas besoin de le refaire ici.

        this.bossManager = new BossManager(this);

        cleanupAndRestoreState();

        // Enregistrement du PlayerListener uniquement.
        // Le SuperiorListener a été supprimé car sa logique est maintenant dans onLoad.
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        getLogger().info("SHEggDragonBoss a été activé avec succès !");
    }

    @Override
    public void onDisable() {
        if (bossManager != null) {
            bossManager.saveData();
            bossManager.cancelAllHatchingProcesses(true);
        }
        getLogger().info("SHEggDragonBoss a été désactivé.");
    }

    /**
     * Enregistre la permission custom définie dans le config.yml.
     * Appelé depuis onLoad pour précéder l'initialisation de SSB2.
     */
    private void registerIslandPermission() {
        try {
            // Sécurité : Vérifier si la classe IslandPrivilege est accessible
            Class.forName("com.bgsoftware.superiorskyblock.api.island.IslandPrivilege");

            String permissionName = configManager.getIslandPermissionName();
            boolean alreadyExists = false;

            // Vérification via l'API
            try {
                if (IslandPrivilege.getByName(permissionName) != null) {
                    alreadyExists = true;
                }
            } catch (Exception ignored) {
                // Fallback pour anciennes versions API
                for (IslandPrivilege privilege : IslandPrivilege.values()) {
                    if (privilege.getName().equalsIgnoreCase(permissionName)) {
                        alreadyExists = true;
                        break;
                    }
                }
            }

            if (!alreadyExists) {
                IslandPrivilege.register(permissionName);
                getLogger().info("[onLoad] Permission d'île '" + permissionName + "' pré-enregistrée dans SuperiorSkyblock2.");
            }

        } catch (ClassNotFoundException e) {
            getLogger().warning("SuperiorSkyblock2 introuvable durant le onLoad. L'enregistrement de la permission est ignoré.");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Erreur lors de l'enregistrement de la permission dans onLoad.", e);
        }
    }

    private boolean setupDependencies() {
        if (getServer().getPluginManager().getPlugin("SuperiorSkyblock2") == null) {
            getLogger().severe("SuperiorSkyblock2 n'est pas installé.");
            return false;
        }
        if (getServer().getPluginManager().getPlugin("FancyHolograms") == null) {
            getLogger().severe("FancyHolograms n'est pas installé.");
            return false;
        }
        this.fancyHologramsPlugin = (FancyHologramsPlugin) Bukkit.getPluginManager().getPlugin("FancyHolograms");
        return true;
    }

    private void cleanupAndRestoreState() {
        getLogger().info("Nettoyage des chunks et restauration des boss...");
        for (World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getForceLoadedChunks()) {
                if (chunk.getPersistentDataContainer().has(CHUNK_LOAD_KEY, PersistentDataType.BYTE)) {
                    chunk.setForceLoaded(false);
                    chunk.getPersistentDataContainer().remove(CHUNK_LOAD_KEY);
                    getLogger().warning("Chunk d'éclosion orphelin trouvé et libéré à: " + chunk.getX() + ", " + chunk.getZ());
                }
            }
        }
        if (bossManager == null) return;

        // Boss déjà présents dans les mondes chargés ; les autres sont repris à leur chargement (EntitiesLoadEvent)
        int before = bossManager.getActiveBossCount();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof LivingEntity boss && entity.getPersistentDataContainer().has(BOSS_KEY, PersistentDataType.BYTE)) {
                    bossManager.adoptBoss(boss);
                }
            }
        }
        int restoredBosses = bossManager.getActiveBossCount() - before;
        if (restoredBosses > 0) getLogger().info(restoredBosses + " boss actifs ont été restaurés.");
    }

    public static SHEggDragonBoss getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public BossManager getBossManager() {
        return bossManager;
    }

    public FancyHologramsPlugin getFancyHologramsPlugin() {
        return fancyHologramsPlugin;
    }

    public LogManager getLogManager() {
        return logManager;
    }
}
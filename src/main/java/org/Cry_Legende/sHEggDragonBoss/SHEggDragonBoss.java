package org.Cry_Legende.sHEggDragonBoss;

import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import org.Cry_Legende.sHEggDragonBoss.commands.BossCommand;
import org.Cry_Legende.sHEggDragonBoss.config.Messages;
import org.Cry_Legende.sHEggDragonBoss.listeners.HeartListener;
import org.Cry_Legende.sHEggDragonBoss.listeners.PlayerListener;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.ConfigManager;
import org.Cry_Legende.sHEggDragonBoss.managers.HeartManager;
import org.Cry_Legende.sHEggDragonBoss.managers.LogManager;
import org.Cry_Legende.sHEggDragonBoss.network.NetworkLink;
import org.Cry_Legende.sHEggDragonBoss.network.ShNetworkLink;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class SHEggDragonBoss extends JavaPlugin {

    /**
     * Fil principal du serveur, relevé à l'activation. Avec les ticks de mondes en parallèle (DivineMC), les fils des
     * mondes sont aussi des fils de tick : Bukkit.isPrimaryThread() y répond vrai, il ne sert donc pas à les distinguer.
     */
    private static volatile Thread serverThread;

    /** Vrai uniquement sur le fil principal du serveur (jamais sur le fil d'un monde ou d'une région). */
    public static boolean onServerThread() {
        return Thread.currentThread() == serverThread;
    }


    private static SHEggDragonBoss instance;
    private ConfigManager configManager;
    private Messages messages;
    private BossManager bossManager;
    private HeartManager heartManager;
    private FancyHologramsPlugin fancyHologramsPlugin;
    private LogManager logManager;
    // Non nul seulement avec SHSkyBridge et SHNetwork (plusieurs serveurs se partagent les îles) : un serveur ne fait vivre
    // que les boss et rituels des îles qu'il héberge, n'en supprime aucun pour une île inconnue ici, et les commandes
    // de récompense passent par SHNetwork (exécutées là où se trouve le joueur)
    private NetworkLink network;

    public static NamespacedKey CHUNK_LOAD_KEY;
    public static NamespacedKey BOSS_KEY;
    /** Type du boss (dragon, creaking) ; une entité sans cette clé est un dragon (boss créés avant le second boss). */
    public static NamespacedKey BOSS_TYPE_KEY;
    /** Creaking : nombre de phases d'invulnérabilité déjà déclenchées. */
    public static NamespacedKey CREAKING_PHASE_KEY;
    /** Creaking : cœurs qu'il reste à détruire dans la phase en cours. */
    public static NamespacedKey CREAKING_HEARTS_KEY;
    /** Dragon : nombre de phases (rugissements) déjà déclenchées. */
    public static NamespacedKey DRAGON_PHASE_KEY;
    /**
     * PV max et PV du boss, réécrits chaque seconde. Purpur (purpur.yml, mobs.phantom.attributes.max_health) remet les PV max
     * du Phantom à 20 quand son chunk se recharge : sans cette copie, le dragon reprenait 20 PV et la perdait pour toujours.
     */
    public static NamespacedKey BOSS_MAX_HEALTH_KEY;
    public static NamespacedKey BOSS_HEALTH_KEY;

    /**
     * CORRECTION : Utilisation de onLoad() pour enregistrer la permission.
     * Cette méthode s'exécute AVANT le onEnable() de SuperiorSkyblock2 (grâce au 'depend' dans plugin.yml).
     * Cela garantit que la permission existe dans le registre avant que SSB2 ne charge les données des îles.
     */
    @Override
    public void onLoad() {
        instance = this; // On initialise l'instance tôt pour le ConfigManager

        // 1. On doit charger la configuration MAINTENANT pour connaître le nom de la permission ; les textes d'abord (le ConfigManager les lit)
        this.messages = new Messages(this);
        messages.load();
        this.configManager = new ConfigManager(this);
        configManager.loadConfig();

        // 2. On enregistre la permission immédiatement
        registerIslandPermission();
    }

    @Override
    public void onEnable() {
        serverThread = Thread.currentThread();
        // Note: 'instance' et 'configManager' sont déjà initialisés dans onLoad().

        CHUNK_LOAD_KEY = new NamespacedKey(this, "forced_chunk_load");
        BOSS_KEY = new NamespacedKey(this, "shegg_boss");
        BOSS_TYPE_KEY = new NamespacedKey(this, "shegg_boss_type");
        CREAKING_PHASE_KEY = new NamespacedKey(this, "shegg_creaking_phase");
        CREAKING_HEARTS_KEY = new NamespacedKey(this, "shegg_creaking_hearts");
        DRAGON_PHASE_KEY = new NamespacedKey(this, "shegg_dragon_phase");
        BOSS_MAX_HEALTH_KEY = new NamespacedKey(this, "shegg_boss_max_health");
        BOSS_HEALTH_KEY = new NamespacedKey(this, "shegg_boss_health");

        if (!setupDependencies()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.logManager = new LogManager(this);

        // Avant les managers : le chargement des rituels et des boss dormants consulte déjà le mode réseau
        enableNetwork();

        // configManager.loadConfig(); <-- Déjà fait dans onLoad(), pas besoin de le refaire ici.

        // Les cœurs du Creaking d'abord : un boss repris au démarrage peut en reposer
        this.heartManager = new HeartManager(this);
        this.bossManager = new BossManager(this);

        cleanupAndRestoreState();

        // Le SuperiorListener a été supprimé car sa logique est maintenant dans onLoad.
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        getServer().getPluginManager().registerEvents(new HeartListener(heartManager), this);

        PluginCommand command = getCommand("sheggdragonboss");
        if (command != null) {
            BossCommand bossCommand = new BossCommand(this);
            command.setExecutor(bossCommand);
            command.setTabCompleter(bossCommand);
        }

        getLogger().info("SHEggDragonBoss a été activé avec succès !");
    }

    @Override
    public void onDisable() {
        if (bossManager != null) {
            bossManager.saveData();
            bossManager.cancelAllRituals(true);
        }
        // Après les boss : plus aucun cœur du Creaking ne doit rester posé dans les mondes
        if (heartManager != null) {
            heartManager.shutdown();
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

    /**
     * Passe en mode réseau si SHSkyBridge et SHNetwork sont installés et 'reseau.actif' est vrai. Les classes qui touchent
     * à eux ne sont chargées qu'ici : sans eux, le plugin se comporte exactement comme avant.
     */
    private void enableNetwork() {
        if (!getConfig().getBoolean("reseau.actif", true)) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("SHNetwork") || !Bukkit.getPluginManager().isPluginEnabled("SHSkyBridge")) return;
        try {
            network = ShNetworkLink.open();
            getLogger().info("SHSkyBridge détecté : seuls les boss et les rituels des îles hébergées par ce serveur sont repris et jamais supprimés pour une île inconnue.");
        } catch (Exception | LinkageError e) {
            network = null;
            getLogger().log(Level.SEVERE, "SHSkyBridge ou SHNetwork présent mais inutilisable, fonctionnement d'un serveur seul : " + e);
        }
    }

    /** Mode réseau actif (plusieurs serveurs hébergent des îles). */
    public boolean isNetwork() {
        return network != null;
    }

    /** Lien avec SHNetwork et SHSkyBridge, ou null sur un serveur seul. */
    public NetworkLink getNetwork() {
        return network;
    }

    /**
     * Vrai si l'île est hébergée par ce serveur : toujours vrai sans mode réseau ; une île inconnue ici (null) n'est
     * jamais considérée comme hébergée, ses chunks n'ont rien de sûr.
     */
    public boolean isHosted(Island island) {
        if (network == null) return true;
        return island != null && network.isHostedHere(island.getUniqueId());
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

    /**
     * Relit messages.yml puis config.yml. Les boss en cours reprennent les nouveaux réglages à leur tick suivant ;
     * le nom du privilège d'île (island-permission) n'est lu qu'au démarrage.
     *
     * @return le nombre d'avertissements de lecture des deux fichiers
     */
    public int reloadAll() {
        messages.load();
        configManager.loadConfig();
        return messages.warnings() + configManager.warnings();
    }

    public static SHEggDragonBoss getInstance() {
        return instance;
    }

    public Messages getMessages() {
        return messages;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public BossManager getBossManager() {
        return bossManager;
    }

    public HeartManager getHeartManager() {
        return heartManager;
    }

    public FancyHologramsPlugin getFancyHologramsPlugin() {
        return fancyHologramsPlugin;
    }

    public LogManager getLogManager() {
        return logManager;
    }
}
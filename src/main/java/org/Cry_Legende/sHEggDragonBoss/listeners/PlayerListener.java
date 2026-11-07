package org.Cry_Legende.sHEggDragonBoss.listeners;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.boss.BossController;
import org.Cry_Legende.sHEggDragonBoss.boss.BossType;
import org.Cry_Legende.sHEggDragonBoss.boss.CreakingBossController;
import org.Cry_Legende.sHEggDragonBoss.boss.DeathEffects;
import org.Cry_Legende.sHEggDragonBoss.config.BossSettings;
import org.Cry_Legende.sHEggDragonBoss.config.RitualSettings;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.ConfigManager;
import org.Cry_Legende.sHEggDragonBoss.managers.HeartManager;
import org.Cry_Legende.sHEggDragonBoss.managers.RewardManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rituels (clic droit sur le bloc, casse du bloc) et vie des boss (dégâts, mort, reprise après redémarrage).
 * Les deux boss passent par les mêmes gestionnaires ; seul le Creaking a une logique en plus (invulnérabilité de phase).
 */
public class PlayerListener implements Listener {

    private final SHEggDragonBoss plugin;
    private final BossManager bossManager;
    private final HeartManager heartManager;
    private final ConfigManager configManager;

    public PlayerListener(SHEggDragonBoss plugin) {
        this.plugin = plugin;
        this.bossManager = plugin.getBossManager();
        this.heartManager = plugin.getHeartManager();
        this.configManager = plugin.getConfigManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        // Le clic est aussi signalé pour la main secondaire : l'objet de la main principale serait compté deux fois
        if (event.getHand() != EquipmentSlot.HAND) return;
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;
        RitualSettings ritual = configManager.ritualForBlock(clickedBlock.getType());
        if (ritual == null) return;
        Player player = event.getPlayer();
        if (player.getInventory().getItemInMainHand().getType() != ritual.item()) return;

        event.setCancelled(true);
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(player);
        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(clickedBlock.getLocation());

        if (island == null) {
            send(player, configManager.common().notOnIsland());
            return;
        }

        // Même privilège d'île pour tous les rituels
        IslandPrivilege privilege = IslandPrivilege.getByName(configManager.getIslandPermissionName());
        if (privilege == null || !island.hasPermission(superiorPlayer, privilege)) {
            send(player, configManager.common().noPermission());
            return;
        }

        bossManager.handleRitualInteraction(ritual, player, clickedBlock, player.isSneaking());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        RitualSettings ritual = configManager.ritualForBlock(block.getType());
        if (ritual == null) return;
        if (!bossManager.handleRitualBreak(block.getLocation())) {
            event.setCancelled(true);
            send(event.getPlayer(), ritual.lockedMessage());
        }
    }

    /**
     * Dégâts reçus par un boss : causes ignorées, puis filtre du boss (phases, plafond par coup), puis coup fatal retenu pour
     * que le boss meure en plusieurs temps. Le coup de grâce du plugin passe sans aucun filtre.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossDamageFilter(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!entity.getPersistentDataContainer().has(SHEggDragonBoss.BOSS_KEY, PersistentDataType.BYTE)) return;
        BossType type = BossType.of(entity.getPersistentDataContainer());
        if (type == null) return;
        BossController controller = bossManager.getController(entity.getUniqueId());
        if (controller != null && controller.isFinishing()) return;
        BossSettings settings = configManager.bossSettings(type);
        if (settings.immuneCauses().contains(event.getCause())) {
            event.setCancelled(true);
            return;
        }
        if (controller == null) return;
        // Pendant la mort le boss est intouchable (sauf /kill et le vide, que interceptLethal laisse passer)
        if (controller.isDying()) {
            if (controller.interceptLethal(event)) event.setCancelled(true);
            return;
        }
        if (controller.absorbDamage(event) || controller.interceptLethal(event)) {
            event.setCancelled(true);
        }
    }

    /** Coup de mêlée vanille du Creaking sur un joueur : le boss y ajoute une part de ses PV max, que l'armure n'arrête pas. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreakingMelee(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (bossManager.getController(event.getDamager().getUniqueId()) instanceof CreakingBossController creaking) {
            creaking.onMeleeHit(victim);
        }
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!entity.getPersistentDataContainer().has(SHEggDragonBoss.BOSS_KEY, PersistentDataType.BYTE)) return;
        BossType type = BossType.of(entity.getPersistentDataContainer());
        if (type == null) return;
        BossSettings settings = configManager.bossSettings(type);

        // Le contrôleur sait qui a porté le coup fatal et ce que chacun a fait au boss : à lire avant qu'il ne soit retiré
        BossController controller = bossManager.getController(entity.getUniqueId());
        Player finisher = controller != null ? controller.deathKiller() : null;
        Player killer = finisher != null ? finisher : entity.getKiller();
        Map<UUID, Double> contributions = controller != null ? controller.contributions() : Map.of();
        Collection<Player> audience = controller != null ? controller.audience() : List.of();
        double maxHealth = controller != null ? controller.maxHealth() : entity.getHealth();
        boolean stagedFinale = controller != null && controller.finaleDone();

        bossManager.handleBossDeath(entity);
        Location loc = entity.getLocation();

        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(loc);
        var messages = plugin.getMessages();
        String islandName = (island != null && island.getOwner() != null)
            ? messages.journal("island-of", "owner", island.getOwner().getName())
            : messages.journal("island-unknown");
        String killerName = killer != null ? killer.getName() : messages.journal("killer-unknown");
        plugin.getLogManager().log(messages.journal(type == BossType.CREAKING ? "creaking-killed" : "dragon-killed",
            "island", islandName, "killer", killerName));

        // Mort sans la scène (un autre plugin a mis ses PV à 0, /kill...) : le final éclate quand même
        if (!stagedFinale && settings.death().enabled()) {
            DeathEffects.finale(plugin, settings.death(), loc.clone().add(0, entity.getHeight() / 2.0, 0), audience);
        }
        settings.deathSound().play(loc);
        event.getDrops().clear();
        event.setDroppedExp(0);

        RewardManager.distribute(plugin, type, settings, contributions, maxHealth, killer, island, loc, audience);
    }

    // Un boss sauvegardé avec son chunk n'a plus de contrôleur après un redémarrage ou un déchargement : on le reprend
    // quand ses entités sont chargées (les mondes de PhantomWorlds arrivent après onEnable)
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        List<LivingEntity> bosses = event.getEntities().stream()
            .filter(e -> e instanceof LivingEntity && e.getPersistentDataContainer().has(SHEggDragonBoss.BOSS_KEY, PersistentDataType.BYTE))
            .map(e -> (LivingEntity) e)
            .toList();
        if (bosses.isEmpty()) return;
        // Un tick plus tard : les entités sont alors bien ajoutées au monde
        Bukkit.getScheduler().runTask(plugin, () -> bosses.forEach(bossManager::adoptBoss));
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        bossManager.onWorldLoad(event.getWorld());
    }

    @EventHandler
    public void onEntityCombust(EntityCombustEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(SHEggDragonBoss.BOSS_KEY, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamage(EntityDamageEvent event) {
        if (!event.getEntity().getPersistentDataContainer().has(SHEggDragonBoss.BOSS_KEY, PersistentDataType.BYTE))
            return;
        BossType type = BossType.of(event.getEntity().getPersistentDataContainer());
        if (type == null) return;

        // Les PV réellement retirés au boss sont comptés à celui qui les a infligés (récompenses des participants)
        BossController controller = bossManager.getController(event.getEntity().getUniqueId());
        if (controller != null && !controller.isDying()) controller.recordDamage(event);

        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            if (byEntityEvent.getDamager() instanceof Player attacker) {
                Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(event.getEntity().getLocation());
                if (island != null && !island.isMember(SuperiorSkyblockAPI.getPlayer(attacker))) {
                    bossManager.setBossExternalTarget((LivingEntity) event.getEntity(), attacker);
                }
            }
        }

        if (event.getFinalDamage() > 0) {
            configManager.bossSettings(type).hurtSound().play(event.getEntity().getLocation());
        }
    }

    private static void send(Player player, String message) {
        if (message != null && !message.isEmpty()) player.sendMessage(message);
    }
}

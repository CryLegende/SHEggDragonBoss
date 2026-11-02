package org.Cry_Legende.sHEggDragonBoss.listeners;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.boss.BossController;
import org.Cry_Legende.sHEggDragonBoss.boss.BossType;
import org.Cry_Legende.sHEggDragonBoss.boss.CreakingBossController;
import org.Cry_Legende.sHEggDragonBoss.config.BossSettings;
import org.Cry_Legende.sHEggDragonBoss.config.Rewards;
import org.Cry_Legende.sHEggDragonBoss.config.RitualSettings;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.ConfigManager;
import org.Cry_Legende.sHEggDragonBoss.managers.HeartManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

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
        // Un cœur de combat est un bloc du même type que le cœur du rituel du Creaking, mais n'est pas un autel
        if (heartManager.isHeart(clickedBlock)) return;
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
        // Les cœurs de combat sont protégés par HeartListener, pas par le rituel
        if (heartManager.isHeart(block)) return;
        if (!bossManager.handleRitualBreak(block.getLocation())) {
            event.setCancelled(true);
            send(event.getPlayer(), ritual.lockedMessage());
        }
    }

    /** Dégâts reçus par un boss : causes ignorées, puis invulnérabilité de phase du Creaking. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossDamageFilter(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!entity.getPersistentDataContainer().has(SHEggDragonBoss.BOSS_KEY, PersistentDataType.BYTE)) return;
        BossType type = BossType.of(entity.getPersistentDataContainer());
        if (type == null) return;
        BossSettings settings = configManager.bossSettings(type);
        if (settings.immuneCauses().contains(event.getCause())) {
            event.setCancelled(true);
            return;
        }
        if (type == BossType.CREAKING && bossManager.getController(entity.getUniqueId()) instanceof CreakingBossController creaking
            && creaking.absorbDamage(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!entity.getPersistentDataContainer().has(SHEggDragonBoss.BOSS_KEY, PersistentDataType.BYTE)) return;
        BossType type = BossType.of(entity.getPersistentDataContainer());
        if (type == null) return;
        BossSettings settings = configManager.bossSettings(type);

        bossManager.handleBossDeath(entity);
        Location loc = entity.getLocation();
        Player killer = entity.getKiller();

        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(loc);
        String islandName = (island != null && island.getOwner() != null) ? "l'île de " + island.getOwner().getName() : "une île inconnue";
        String killerName = killer != null ? killer.getName() : "Cause inconnue";
        if (type == BossType.CREAKING) {
            plugin.getLogManager().log(String.format("Le boss Creaking sur %s a été tué par %s.", islandName, killerName));
        } else {
            plugin.getLogManager().log(String.format("Le boss sur %s a été tué par %s.", islandName, killerName));
        }

        settings.deathSound().play(loc);
        event.getDrops().clear();
        event.setDroppedExp(0);

        Rewards rewards = settings.rewards();
        if (killer != null && rewards.xp() > 0) {
            killer.giveExp(rewards.xp());
        }

        for (Rewards.ItemReward reward : rewards.items()) {
            ItemStack itemStack = new ItemStack(reward.material(), reward.amount());
            if (reward.name() != null || !reward.lore().isEmpty()) {
                ItemMeta meta = itemStack.getItemMeta();
                if (meta != null) {
                    if (reward.name() != null) meta.setDisplayName(reward.name());
                    if (!reward.lore().isEmpty()) meta.setLore(reward.lore());
                    itemStack.setItemMeta(meta);
                }
            }
            loc.getWorld().dropItemNaturally(loc, itemStack);
        }

        executeDeathCommands(rewards.commands(), killer, island, loc);
    }

    private void executeDeathCommands(List<String> commands, Player killer, Island island, Location loc) {
        if (commands.isEmpty()) return;

        String islandDisplay = "";
        if (island != null) {
            if (island.getName() != null && !island.getName().isEmpty()) {
                islandDisplay = island.getName();
            } else if (island.getOwner() != null) {
                islandDisplay = island.getOwner().getName();
            }
        }

        for (String rawCommand : commands) {
            if (rawCommand == null || rawCommand.trim().isEmpty()) continue;
            if (killer == null && rawCommand.contains("{player}")) continue;

            String command = rawCommand.trim();
            if (command.startsWith("/")) command = command.substring(1);

            command = command
                .replace("{player}", killer != null ? killer.getName() : "")
                .replace("{island}", islandDisplay)
                .replace("{world}", loc.getWorld().getName())
                .replace("{x}", String.valueOf(loc.getBlockX()))
                .replace("{y}", String.valueOf(loc.getBlockY()))
                .replace("{z}", String.valueOf(loc.getBlockZ()));
            command = ChatColor.translateAlternateColorCodes('&', command);

            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } catch (Exception e) {
                plugin.getLogger().warning("Erreur lors de l'exécution de la commande de mort du boss '" + command + "' : " + e.getMessage());
            }
        }
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

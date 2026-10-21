package org.Cry_Legende.sHEggDragonBoss.listeners;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.island.IslandPrivilege;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
import org.Cry_Legende.sHEggDragonBoss.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public class PlayerListener implements Listener {

    private final SHEggDragonBoss plugin;
    private final BossManager bossManager;
    private final ConfigManager configManager;

    public PlayerListener(SHEggDragonBoss plugin) {
        this.plugin = plugin;
        this.bossManager = plugin.getBossManager();
        this.configManager = plugin.getConfigManager();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null || clickedBlock.getType() != Material.DRAGON_EGG) return;
        Player player = event.getPlayer();
        if (player.getInventory().getItemInMainHand().getType() != configManager.getRequiredItem()) return;

        event.setCancelled(true);
        SuperiorPlayer superiorPlayer = SuperiorSkyblockAPI.getPlayer(player);
        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(clickedBlock.getLocation());

        if (island == null) {
            player.sendMessage(configManager.getNotOnIsland());
            return;
        }

        // CORRECTION : Logique de permission restaurée, conforme à l'API.
        IslandPrivilege eggHatchPrivilege = IslandPrivilege.getByName(configManager.getIslandPermissionName());
        if (eggHatchPrivilege == null || !island.hasPermission(superiorPlayer, eggHatchPrivilege)) {
            player.sendMessage(configManager.getNoPermission());
            return;
        }

        bossManager.handleEggInteraction(player, clickedBlock, player.isSneaking());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.DRAGON_EGG) return;
        if (!bossManager.handleEggBreak(block.getLocation())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(configManager.getAlreadyHatching());
        }
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!entity.getPersistentDataContainer().has(SHEggDragonBoss.BOSS_KEY, PersistentDataType.BYTE)) return;

        bossManager.handleBossDeath(entity);
        Location loc = entity.getLocation();
        Player killer = entity.getKiller();

        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(loc);
        String islandName = (island != null && island.getOwner() != null) ? "l'île de " + island.getOwner().getName() : "une île inconnue";
        plugin.getLogManager().log(String.format("Le boss sur %s a été tué par %s.", islandName, (killer != null ? killer.getName() : "Cause inconnue")));

        loc.getWorld().playSound(loc, configManager.getBossDeathSound(), configManager.getBossDeathSoundVolume(), configManager.getBossDeathSoundPitch());
        event.getDrops().clear();
        event.setDroppedExp(0);

        if (killer != null && configManager.getXpReward() > 0) {
            killer.giveExp(configManager.getXpReward());
        }

        for (ConfigManager.ItemReward reward : configManager.getItemRewards()) {
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

        executeDeathCommands(killer, island, loc);
    }

    private void executeDeathCommands(Player killer, Island island, Location loc) {
        List<String> commands = configManager.getDeathCommands();
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
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.SUFFOCATION || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION || cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) {
            event.setCancelled(true);
            return;
        }

        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            if (byEntityEvent.getDamager() instanceof Player attacker) {
                Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(event.getEntity().getLocation());
                if (island != null && !island.isMember(SuperiorSkyblockAPI.getPlayer(attacker))) {
                    bossManager.setBossExternalTarget((LivingEntity) event.getEntity(), attacker);
                }
            }
        }

        if (event.getFinalDamage() > 0) {
            Location loc = event.getEntity().getLocation();
            loc.getWorld().playSound(loc, configManager.getBossHurtSound(), configManager.getBossHurtSoundVolume(), configManager.getBossHurtSoundPitch());
        }
    }
}

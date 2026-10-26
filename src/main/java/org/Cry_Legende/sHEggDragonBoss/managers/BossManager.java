package org.Cry_Legende.sHEggDragonBoss.managers;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.boss.BossController;
import org.Cry_Legende.sHEggDragonBoss.hatching.HatchingProcess;
import org.Cry_Legende.sHEggDragonBoss.tasks.HatchingTask;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class BossManager {

    private final SHEggDragonBoss plugin;
    private final ConfigManager configManager;
    private final BossDataManager dataManager;

    private final Map<Location, HatchingProcess> hatchingProcesses;
    private final Map<UUID, BossController> activeBosses;
    private final Map<UUID, BossDataManager.BossData> dormantBosses;

    public BossManager(SHEggDragonBoss plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.dataManager = new BossDataManager(plugin);
        this.activeBosses = new ConcurrentHashMap<>();
        this.hatchingProcesses = dataManager.loadHatchingData();
        this.dormantBosses = dataManager.loadDormantBosses();

        new DormantBossCheckerTask().runTaskTimer(plugin, 100L, 60L);
        new BossGuardianTask().runTaskTimer(plugin, 120L, 40L);
    }

    public void saveData() {
        dataManager.saveHatchingData(hatchingProcesses);
        dataManager.saveDormantBosses(dormantBosses);
    }

    public void handleEggInteraction(Player player, Block eggBlock, boolean isShiftClick) {
        Location eggLocation = eggBlock.getLocation();
        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(eggLocation);
        if (isDragonActiveOnIsland(island)) {
            player.sendMessage(configManager.getDragonActiveOnIsland());
            return;
        }
        HatchingProcess process = hatchingProcesses.computeIfAbsent(eggLocation, loc -> {
            player.sendMessage(configManager.getStartHatching());
            return new HatchingProcess(plugin, loc);
        });
        if (process.isCountdownStarted()) {
            player.sendMessage(configManager.getAlreadyHatching());
            return;
        }
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        int amountToAdd = isShiftClick ? itemInHand.getAmount() : 1;
        int addedShards = process.addShards(amountToAdd, configManager.getRequiredShards());
        if (addedShards > 0) {
            itemInHand.setAmount(itemInHand.getAmount() - addedShards);
            playAddShardEffect(eggLocation);
            saveData();
        }
        process.updateHologram(configManager);
        if (process.getCurrentShards() >= configManager.getRequiredShards()) {
            startCountdown(process);
        }
    }

    public boolean handleEggBreak(Location eggLocation) {
        HatchingProcess process = hatchingProcesses.get(eggLocation);
        if (process != null) {
            if (process.isCountdownStarted()) return false;
            process.destroy();
            hatchingProcesses.remove(eggLocation);
            if (process.getCurrentShards() > 0) {
                eggLocation.getWorld().dropItemNaturally(eggLocation.clone().add(0.5, 1, 0.5), new ItemStack(configManager.getRequiredItem(), process.getCurrentShards()));
            }
            saveData();
        }
        return true;
    }

    private void startCountdown(HatchingProcess process) {
        process.setCountdownStarted(true);
        process.startEggAnimation();
        process.transformHologramForCountdown(configManager);
        Chunk chunk = process.getLocation().getChunk();
        chunk.getPersistentDataContainer().set(SHEggDragonBoss.CHUNK_LOAD_KEY, PersistentDataType.BYTE, (byte) 1);
        chunk.setForceLoaded(true);
        Location loc = process.getLocation();
        loc.getWorld().playSound(loc, configManager.getHatchingStartSound(), configManager.getHatchingStartVolume(), configManager.getHatchingStartPitch());
        BukkitTask task = new HatchingTask(this, process, configManager).runTaskTimer(plugin, 0L, 1L);
        process.setTask(task);
    }

    public void spawnBoss(HatchingProcess process) {
        Location spawnLocation = process.getLocation().clone().add(0.5, 2.0, 0.5);
        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(spawnLocation);
        process.destroy();
        hatchingProcesses.remove(process.getLocation());
        releaseHatchingChunk(process.getLocation());
        saveData();
        if (island == null) {
            // Île supprimée pendant le compte à rebours : le contrôleur du boss en a besoin pour cibler les joueurs
            plugin.getLogger().warning("Éclosion annulée : plus d'île à " + spawnLocation.getBlockX() + " " + spawnLocation.getBlockY() + " " + spawnLocation.getBlockZ() + ".");
            return;
        }
        double baseHealth = configManager.getBossHealth();
        double dynamicHealth = 0;
        if (configManager.isDynamicStatsEnabled()) {
            dynamicHealth += island.getIslandLevel().doubleValue() * configManager.getHealthPerIslandLevel();
            dynamicHealth += island.getIslandMembers(true).size() * configManager.getHealthPerIslandMember();
        }
        new BossController(plugin, this, island, spawnLocation, baseHealth + dynamicHealth, baseHealth + dynamicHealth, true);
    }

    private void respawnDormantBoss(BossDataManager.BossData data) {
        Island island = SuperiorSkyblockAPI.getIslandByUUID(data.islandId());
        if (island == null) return;
        new BossController(plugin, this, island, data.lastLocation(), data.maxHealth(), data.currentHealth(), false);
    }

    public void restoreBoss(Island island, LivingEntity boss) {
        // Le boss est bien là : l'entrée "dormant" de cette île ne doit plus refaire apparaître un second boss
        dormantBosses.remove(island.getUniqueId());
        new BossController(plugin, this, island, boss, boss.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getBaseValue());
    }

    /**
     * Reprend un boss trouvé dans le monde (redémarrage, chunk rechargé, monde chargé tard) s'il n'a plus de contrôleur.
     * Un second boss sur la même île, ou hors de toute île, est retiré : sinon il resterait figé et tuable pour la récompense.
     */
    public void adoptBoss(LivingEntity boss) {
        if (!boss.isValid() || activeBosses.containsKey(boss.getUniqueId())) return;
        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(boss.getLocation());
        if (island == null) {
            plugin.getLogger().warning("Un boss a été trouvé en dehors d'une île et a été supprimé.");
            boss.remove();
        } else if (hasActiveBoss(island)) {
            plugin.getLogger().warning("Un boss en double a été trouvé sur une île déjà occupée et a été supprimé.");
            boss.remove();
        } else {
            restoreBoss(island, boss);
        }
    }

    public int getActiveBossCount() {
        return activeBosses.size();
    }

    private boolean hasActiveBoss(Island island) {
        return activeBosses.values().stream().anyMatch(c -> c.getIsland().getUniqueId().equals(island.getUniqueId()));
    }

    /** Active les données (éclosions, boss dormants) en attente d'un monde qui vient de se charger. */
    public void onWorldLoad(World world) {
        hatchingProcesses.putAll(dataManager.resolveHatching(world));
        dormantBosses.putAll(dataManager.resolveDormant(world));
    }

    public void registerActiveBoss(BossController controller) {
        activeBosses.put(controller.getUniqueId(), controller);
    }

    public void handleBossDeath(LivingEntity boss) {
        BossController controller = activeBosses.remove(boss.getUniqueId());
        if (controller != null) {
            if (!controller.isCancelled()) {
                controller.cancel();
            }
            dormantBosses.remove(controller.getIsland().getUniqueId());
            saveData();
        }
    }

    public void salvageLostBoss(BossController controller) {
        if (controller == null) return;

        plugin.getLogger().warning("Le boss " + controller.getUniqueId() + " a été détecté comme invalide (despawn). Sauvegarde en mode dormant.");

        activeBosses.remove(controller.getUniqueId());
        dormantBosses.put(controller.getIsland().getUniqueId(), controller.getBossData());
        saveData();

        if (!controller.isCancelled()) {
            controller.cancel();
        }
    }

    public void setBossExternalTarget(LivingEntity boss, Player attacker) {
        BossController controller = activeBosses.get(boss.getUniqueId());
        if (controller != null) {
            controller.setExternalTarget(attacker);
        }
    }

    public boolean isDragonActiveOnIsland(Island island) {
        if (island == null) return false;
        return hasActiveBoss(island) || dormantBosses.containsKey(island.getUniqueId());
    }

    public void cancelAllHatchingProcesses(boolean releaseChunks) {
        for (HatchingProcess process : hatchingProcesses.values()) {
            // Compte à rebours en cours : l'œuf avait été retiré du monde, on le remet pour qu'il ne soit pas perdu avec ses éclats
            if (process.isCountdownStarted() && process.getLocation().getBlock().getType() == Material.AIR) {
                process.getLocation().getBlock().setType(Material.DRAGON_EGG);
            }
            process.destroy();
            if (releaseChunks) releaseHatchingChunk(process.getLocation());
        }
        hatchingProcesses.clear();
        activeBosses.values().forEach(BukkitRunnable::cancel);
        activeBosses.clear();
    }

    /** Libère le chunk forcé pendant le compte à rebours (sinon il reste chargé jusqu'au prochain redémarrage). */
    private void releaseHatchingChunk(Location location) {
        // Chunk non chargé : il n'a pas pu être forcé, inutile de le charger pour rien
        if (location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        Chunk chunk = location.getChunk();
        if (chunk.getPersistentDataContainer().has(SHEggDragonBoss.CHUNK_LOAD_KEY, PersistentDataType.BYTE)) {
            chunk.setForceLoaded(false);
            chunk.getPersistentDataContainer().remove(SHEggDragonBoss.CHUNK_LOAD_KEY);
        }
    }

    private void playAddShardEffect(Location location) {
        World world = location.getWorld();
        world.playSound(location, configManager.getAddShardSound(), configManager.getAddShardVolume(), configManager.getAddShardPitch());
        world.spawnParticle(Particle.SCULK_SOUL, location.clone().add(0.5, 0.5, 0.5), 20, 0.5, 0.5, 0.5, 0.1);
    }

    private class DormantBossCheckerTask extends BukkitRunnable {
        @Override
        public void run() {
            if (dormantBosses.isEmpty()) return;
            for (UUID islandId : new ArrayList<>(dormantBosses.keySet())) {
                Island island = SuperiorSkyblockAPI.getIslandByUUID(islandId);
                if (island == null) {
                    dormantBosses.remove(islandId);
                    continue;
                }
                boolean playerFound = island.getIslandMembers(true).stream().map(SuperiorPlayer::asPlayer).anyMatch(p -> p != null && p.isOnline() && p.getGameMode() != org.bukkit.GameMode.SPECTATOR && island.isInside(p.getLocation()));
                if (playerFound) {
                    BossDataManager.BossData data = dormantBosses.remove(islandId);
                    if (data != null) {
                        respawnDormantBoss(data);
                        saveData();
                    }
                }
            }
        }
    }

    private class BossGuardianTask extends BukkitRunnable {
        @Override
        public void run() {
            if (activeBosses.isEmpty()) return;

            for (BossController controller : new ArrayList<>(activeBosses.values())) {
                if (!controller.getBoss().isValid() || controller.getBoss().isDead()) {
                    salvageLostBoss(controller);
                }
            }
        }
    }
}

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
        saveData();
        double baseHealth = configManager.getBossHealth();
        double dynamicHealth = 0;
        if (island != null && configManager.isDynamicStatsEnabled()) {
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
        new BossController(plugin, this, island, boss, boss.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getBaseValue());
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
        return activeBosses.values().stream().anyMatch(c -> c.getIsland().getUniqueId().equals(island.getUniqueId())) || dormantBosses.containsKey(island.getUniqueId());
    }

    public void cancelAllHatchingProcesses(boolean releaseChunks) {
        hatchingProcesses.values().forEach(HatchingProcess::destroy);
        hatchingProcesses.clear();
        activeBosses.values().forEach(BukkitRunnable::cancel);
        activeBosses.clear();
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

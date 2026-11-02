package org.Cry_Legende.sHEggDragonBoss.managers;

import com.bgsoftware.superiorskyblock.api.SuperiorSkyblockAPI;
import com.bgsoftware.superiorskyblock.api.island.Island;
import com.bgsoftware.superiorskyblock.api.wrappers.SuperiorPlayer;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.boss.BossController;
import org.Cry_Legende.sHEggDragonBoss.boss.BossType;
import org.Cry_Legende.sHEggDragonBoss.boss.CreakingBossController;
import org.Cry_Legende.sHEggDragonBoss.boss.DragonBossController;
import org.Cry_Legende.sHEggDragonBoss.config.BossSettings;
import org.Cry_Legende.sHEggDragonBoss.config.RitualSettings;
import org.Cry_Legende.sHEggDragonBoss.ritual.RitualProcess;
import org.Cry_Legende.sHEggDragonBoss.ritual.RitualTask;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Creaking;
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

/**
 * Orchestre les rituels, les boss actifs et les boss dormants. Une île n'a jamais qu'un seul boss (de n'importe quel
 * type) ou rituel en cours à la fois.
 */
public class BossManager {

    private final SHEggDragonBoss plugin;
    private final ConfigManager configManager;
    private final BossDataManager dataManager;

    private final Map<Location, RitualProcess> ritualProcesses;
    private final Map<UUID, BossController> activeBosses;
    private final Map<UUID, BossDataManager.BossData> dormantBosses;

    public BossManager(SHEggDragonBoss plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.dataManager = new BossDataManager(plugin);
        this.activeBosses = new ConcurrentHashMap<>();
        this.ritualProcesses = dataManager.loadRitualData();
        this.dormantBosses = dataManager.loadDormantBosses();

        new DormantBossCheckerTask().runTaskTimer(plugin, 100L, 60L);
        new BossGuardianTask().runTaskTimer(plugin, 120L, 40L);
    }

    public void saveData() {
        dataManager.saveRitualData(ritualProcesses);
        dataManager.saveDormantBosses(dormantBosses);
    }

    // ------------------------------------------------------------------ rituels

    public void handleRitualInteraction(RitualSettings ritual, Player player, Block ritualBlock, boolean isShiftClick) {
        Location blockLocation = ritualBlock.getLocation();
        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(blockLocation);
        if (isBusy(island)) {
            send(player, configManager.common().bossAlreadyActive());
            return;
        }
        RitualProcess process = ritualProcesses.computeIfAbsent(blockLocation, loc -> {
            send(player, ritual.startedMessage());
            return new RitualProcess(plugin, ritual, loc);
        });
        if (process.isCountdownStarted()) {
            send(player, ritual.lockedMessage());
            return;
        }
        ItemStack itemInHand = player.getInventory().getItemInMainHand();
        int amountToAdd = isShiftClick ? itemInHand.getAmount() : 1;
        int added = process.addItems(amountToAdd);
        if (added > 0) {
            itemInHand.setAmount(itemInHand.getAmount() - added);
            playAddItemEffect(process);
            saveData();
        }
        process.updateHologram();
        if (process.getCurrentItems() >= ritual.requiredAmount()) {
            startCountdown(process);
        }
    }

    /** Casse du bloc d'un rituel : refusée (faux) pendant le compte à rebours, sinon les objets donnés sont rendus. */
    public boolean handleRitualBreak(Location blockLocation) {
        RitualProcess process = ritualProcesses.get(blockLocation);
        if (process != null) {
            if (process.isCountdownStarted()) return false;
            process.destroy();
            ritualProcesses.remove(blockLocation);
            if (process.getCurrentItems() > 0) {
                blockLocation.getWorld().dropItemNaturally(blockLocation.clone().add(0.5, 1, 0.5), new ItemStack(process.getRitual().item(), process.getCurrentItems()));
            }
            saveData();
        }
        return true;
    }

    /** Vrai si ce bloc est celui d'un rituel dont le compte à rebours est lancé. */
    public boolean isRitualLocked(Location blockLocation) {
        RitualProcess process = ritualProcesses.get(blockLocation);
        return process != null && process.isCountdownStarted();
    }

    private void startCountdown(RitualProcess process) {
        RitualSettings ritual = process.getRitual();
        process.setCountdownStarted(true);
        process.startBlockAnimation();
        process.transformHologramForCountdown();
        Chunk chunk = process.getLocation().getChunk();
        chunk.getPersistentDataContainer().set(SHEggDragonBoss.CHUNK_LOAD_KEY, PersistentDataType.BYTE, (byte) 1);
        chunk.setForceLoaded(true);
        ritual.sounds().start().play(process.getLocation());
        BukkitTask task = new RitualTask(this, process).runTaskTimer(plugin, 0L, 1L);
        process.setTask(task);
    }

    public void spawnBoss(RitualProcess process) {
        RitualSettings ritual = process.getRitual();
        Location spawnLocation = process.getLocation().clone().add(0.5, ritual.spawnOffsetY(), 0.5);
        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(spawnLocation);
        process.destroy();
        ritualProcesses.remove(process.getLocation());
        releaseRitualChunk(process.getLocation());
        saveData();
        if (island == null) {
            // Île supprimée pendant le compte à rebours : le contrôleur du boss en a besoin pour cibler les joueurs
            plugin.getLogger().warning("Éclosion annulée : plus d'île à " + spawnLocation.getBlockX() + " " + spawnLocation.getBlockY() + " " + spawnLocation.getBlockZ() + ".");
            return;
        }
        double health = computeHealth(configManager.bossSettings(ritual.type()), island);
        createController(ritual.type(), island, spawnLocation, health, health, 0, 0, BossController.StartMode.FIRST_SPAWN);
    }

    /** PV du boss sur cette île : {@code base + par-niveau × niveau de l'île + par-membre × membres}. */
    private double computeHealth(BossSettings settings, Island island) {
        double health = settings.health();
        if (settings.dynamicStats().enabled()) {
            health += island.getIslandLevel().doubleValue() * settings.dynamicStats().healthPerIslandLevel();
            health += island.getIslandMembers(true).size() * settings.dynamicStats().healthPerIslandMember();
        }
        return health;
    }

    private void createController(BossType type, Island island, Location location, double maxHealth, double currentHealth, int phase, int heartsLeft, BossController.StartMode mode) {
        switch (type) {
            case DRAGON -> DragonBossController.spawn(plugin, this, island, location, maxHealth, currentHealth, mode);
            case CREAKING -> CreakingBossController.spawn(plugin, this, island, location, maxHealth, currentHealth, phase, heartsLeft, mode);
        }
    }

    // ------------------------------------------------------------------ boss

    private void respawnDormantBoss(BossDataManager.BossData data) {
        Island island = SuperiorSkyblockAPI.getIslandByUUID(data.islandId());
        if (island == null) return;
        createController(data.type(), island, data.lastLocation(), data.maxHealth(), data.currentHealth(), data.phase(), data.heartsLeft(), BossController.StartMode.WOKEN_FROM_DORMANT);
    }

    public void restoreBoss(Island island, LivingEntity boss, BossType type) {
        // Le boss est bien là : l'entrée "dormant" de cette île ne doit plus refaire apparaître un second boss
        dormantBosses.remove(island.getUniqueId());
        AttributeInstance maxHealth = boss.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth != null ? maxHealth.getBaseValue() : boss.getHealth();
        switch (type) {
            case DRAGON -> DragonBossController.adopt(plugin, this, island, boss, max);
            case CREAKING -> {
                if (boss instanceof Creaking creaking) {
                    CreakingBossController.adopt(plugin, this, island, creaking, max);
                } else {
                    plugin.getLogger().warning("Une entité marquée comme Creaking n'en est pas un (" + boss.getType() + ") : elle a été supprimée.");
                    boss.remove();
                }
            }
        }
    }

    /**
     * Reprend un boss trouvé dans le monde (redémarrage, chunk rechargé, monde chargé tard) s'il n'a plus de contrôleur.
     * Un second boss sur la même île, ou hors de toute île, est retiré : sinon il resterait figé et tuable pour la récompense.
     */
    public void adoptBoss(LivingEntity boss) {
        if (!boss.isValid() || activeBosses.containsKey(boss.getUniqueId())) return;
        BossType type = BossType.of(boss.getPersistentDataContainer());
        if (type == null) {
            // Écrit par une version plus récente du plugin : on n'y touche pas
            plugin.getLogger().warning("Un boss de type inconnu a été trouvé à " + boss.getLocation().getBlockX() + " " + boss.getLocation().getBlockY() + " " + boss.getLocation().getBlockZ() + " : ignoré.");
            return;
        }
        Island island = SuperiorSkyblockAPI.getGrid().getIslandAt(boss.getLocation());
        if (island == null) {
            plugin.getLogger().warning("Un boss a été trouvé en dehors d'une île et a été supprimé.");
            boss.remove();
        } else if (hasActiveBoss(island)) {
            plugin.getLogger().warning("Un boss en double a été trouvé sur une île déjà occupée et a été supprimé.");
            boss.remove();
        } else {
            restoreBoss(island, boss, type);
        }
    }

    public int getActiveBossCount() {
        return activeBosses.size();
    }

    public BossController getController(UUID bossId) {
        return activeBosses.get(bossId);
    }

    private boolean hasActiveBoss(Island island) {
        return activeBosses.values().stream().anyMatch(c -> c.getIsland().getUniqueId().equals(island.getUniqueId()));
    }

    /** Active les données (rituels, boss dormants) en attente d'un monde qui vient de se charger. */
    public void onWorldLoad(World world) {
        ritualProcesses.putAll(dataManager.resolveRituals(world));
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

    /** Un boss en vie, un boss dormant ou un rituel en compte à rebours sur cette île : aucun autre rituel ne peut démarrer. */
    public boolean isBusy(Island island) {
        if (island == null) return false;
        if (hasActiveBoss(island) || dormantBosses.containsKey(island.getUniqueId())) return true;
        return ritualProcesses.values().stream().anyMatch(p -> {
            if (!p.isCountdownStarted()) return false;
            Island processIsland = SuperiorSkyblockAPI.getGrid().getIslandAt(p.getLocation());
            return processIsland != null && processIsland.getUniqueId().equals(island.getUniqueId());
        });
    }

    public void cancelAllRituals(boolean releaseChunks) {
        for (RitualProcess process : ritualProcesses.values()) {
            // Compte à rebours en cours : le bloc avait été retiré du monde, on le remet pour qu'il ne soit pas perdu avec ses objets
            if (process.isCountdownStarted() && process.getLocation().getBlock().getType() == Material.AIR) {
                process.getLocation().getBlock().setType(process.getRitual().block());
            }
            process.destroy();
            if (releaseChunks) releaseRitualChunk(process.getLocation());
        }
        ritualProcesses.clear();
        activeBosses.values().forEach(BukkitRunnable::cancel);
        activeBosses.clear();
    }

    /** Libère le chunk forcé pendant le compte à rebours (sinon il reste chargé jusqu'au prochain redémarrage). */
    private void releaseRitualChunk(Location location) {
        // Chunk non chargé : il n'a pas pu être forcé, inutile de le charger pour rien
        if (location.getWorld() == null || !location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) return;
        Chunk chunk = location.getChunk();
        if (chunk.getPersistentDataContainer().has(SHEggDragonBoss.CHUNK_LOAD_KEY, PersistentDataType.BYTE)) {
            chunk.setForceLoaded(false);
            chunk.getPersistentDataContainer().remove(SHEggDragonBoss.CHUNK_LOAD_KEY);
        }
    }

    private void playAddItemEffect(RitualProcess process) {
        RitualSettings ritual = process.getRitual();
        Location center = process.getLocation().clone().add(0.5, 0.5, 0.5);
        ritual.sounds().addItem().play(process.getLocation());
        ritual.particles().addItem().spawn(center);
    }

    private static void send(Player player, String message) {
        if (message != null && !message.isEmpty()) player.sendMessage(message);
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

package org.Cry_Legende.sHEggDragonBoss.listeners;

import org.Cry_Legende.sHEggDragonBoss.managers.HeartManager;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Protège les cœurs du Creaking : seul {@link HeartManager} peut les détruire, et seulement à la main.
 * Aucune casse, explosion, piston, eau ou autre cause ne peut en déplacer ou en casser un.
 * (La physique des blocs est gardée par {@link HeartManager.PhysicsGuard}, enregistré seulement tant qu'un cœur existe.)
 */
public class HeartListener implements Listener {

    private final HeartManager hearts;

    public HeartListener(HeartManager hearts) {
        this.hearts = hearts;
    }

    // MONITOR et ignoreCancelled=false : un coup compte même si une protection d'île a annulé le clic
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onLeftClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (event.getHand() != null && event.getHand() != EquipmentSlot.HAND) return;
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.CREAKING_HEART) return;
        hearts.hit(event.getPlayer(), block);
    }

    // La destruction est gérée par le plugin : on annule même si une protection d'île l'avait déjà fait ou ne l'aurait pas fait
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (hearts.isHeart(event.getBlock())) {
            event.setDropItems(false);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(BlockDamageEvent event) {
        if (hearts.isHeart(event.getBlock())) {
            event.setInstaBreak(false);
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(hearts::isHeart);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(hearts::isHeart);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(hearts::isHeart)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(hearts::isHeart)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFluidFlow(BlockFromToEvent event) {
        if (hearts.isHeart(event.getToBlock())) event.setCancelled(true);
    }

    // Wither, Enderman, Ravageur... : toute créature qui voudrait changer le bloc
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (hearts.isHeart(event.getBlock())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (hearts.isHeart(event.getBlock())) event.setCancelled(true);
    }

    // Cœurs laissés par un arrêt brutal : retirés dès que leur chunk (ou monde) se charge
    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        hearts.onChunkLoad(event.getChunk());
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        hearts.onWorldLoad(event.getWorld());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hearts.forgetPlayer(event.getPlayer().getUniqueId());
    }
}

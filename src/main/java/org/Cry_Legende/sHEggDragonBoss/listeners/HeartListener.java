package org.Cry_Legende.sHEggDragonBoss.listeners;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.Cry_Legende.sHEggDragonBoss.managers.HeartManager;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Les cœurs du Creaking sont des entités (affichage + zone de frappe) : un clic gauche sur la zone de frappe compte un coup.
 * Comme ce ne sont pas des blocs, aucune casse, explosion, piston, eau ou autre cause ne peut les atteindre.
 */
public class HeartListener implements Listener {

    private final HeartManager hearts;

    public HeartListener(HeartManager hearts) {
        this.hearts = hearts;
    }

    // HIGHEST et ignoreCancelled=false : un coup compte même si une protection d'île a annulé l'attaque
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        Entity target = event.getAttacked();
        if (!hearts.isHeart(target)) return;
        event.setCancelled(true);
        hearts.hit(event.getPlayer(), target);
    }

    // Entités de cœur laissées par un arrêt brutal : retirées dès que leur chunk se charge
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        hearts.onEntitiesLoad(event.getEntities());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hearts.forgetPlayer(event.getPlayer().getUniqueId());
    }
}

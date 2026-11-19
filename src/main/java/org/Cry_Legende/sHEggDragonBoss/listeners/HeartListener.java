package org.Cry_Legende.sHEggDragonBoss.listeners;

import org.Cry_Legende.sHEggDragonBoss.managers.HeartManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

/**
 * Les cœurs du Creaking n'existent qu'en paquets (leurs clics sont lus par HeartManager) : ici, seulement le ménage des
 * anciens cœurs en entités réelles (jusqu'à la 1.2) et l'oubli d'un joueur qui part.
 */
public class HeartListener implements Listener {

    private final HeartManager hearts;

    public HeartListener(HeartManager hearts) {
        this.hearts = hearts;
    }

    // Anciens cœurs en entités réelles laissés dans un chunk : retirés dès qu'il se charge
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        hearts.onEntitiesLoad(event.getEntities());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hearts.forgetPlayer(event.getPlayer().getUniqueId());
    }
}

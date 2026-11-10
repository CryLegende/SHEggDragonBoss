package org.Cry_Legende.sHEggDragonBoss.network;

import java.util.UUID;

/**
 * Ce dont le mode réseau a besoin de SHSkyBridge et de SHNetwork. Cette interface ne mentionne aucune de leurs classes :
 * le plugin la manipule sans jamais charger {@link ShNetworkLink}, donc sans eux il ne se passe rien.
 */
public interface NetworkLink {

    /** Vrai si l'île est hébergée par ce serveur : seul cas où ses chunks et ses boss sont à jour ici. */
    boolean isHostedHere(UUID island);

    /** Lance une commande console pour un joueur, là où il se trouve, une fois sa sauvegarde de passage terminée. */
    void runFor(String player, String command);
}

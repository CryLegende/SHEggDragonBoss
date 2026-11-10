package org.Cry_Legende.sHEggDragonBoss.network;

import org.Cry_Legende.sHNetwork.api.SHNetworkAPI;
import org.Cry_Legende.sHSkyBridge.api.SkyBridgeAPI;

import java.util.UUID;

/** Pont vers SHSkyBridge et SHNetwork. Cette classe n'est chargée que si les deux sont installés. */
public final class ShNetworkLink implements NetworkLink {

    private final SHNetworkAPI network;
    private final SkyBridgeAPI hosts;

    private ShNetworkLink() {
        this.network = SHNetworkAPI.get();
        this.hosts = SkyBridgeAPI.get();
    }

    /** Point d'entrée : le plugin ne résout cette classe qu'à cet appel, donc jamais sans SHNetwork ni SHSkyBridge. */
    public static NetworkLink open() {
        return new ShNetworkLink();
    }

    @Override
    public boolean isHostedHere(UUID island) {
        return hosts.isHostedHere(island);
    }

    @Override
    public void runFor(String player, String command) {
        network.runFor(player, command);
    }
}

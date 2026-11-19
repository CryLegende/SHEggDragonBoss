package org.Cry_Legende.sHEggDragonBoss.model;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Envoi des paquets : tout ce qui part vers un joueur lors d'une mise à jour forme un lot, remis en une fois au fil
 * réseau de sa connexion. C'est ce fil qui encode les paquets, les écrit à la suite et les envoie d'un bloc : le fil
 * principal du serveur ne fait que préparer le lot (une remise au lieu d'une par paquet, aucun encodage).
 * Les lots d'un même joueur partent dans l'ordre où ils ont été remis. Un joueur en cours de déconnexion est ignoré.
 */
public final class Packets {

    private Packets() {
    }

    public static int newEntityId() {
        return SpigotReflectionUtil.generateEntityId();
    }

    /**
     * Envoie la liste à un joueur. Les paquets marqués « visibles » passent par les écouteurs des autres plugins
     * (packetevents) : c'est le cas de la liste des passagers du joueur, qu'UnlimitedNametags complète avec ses
     * pseudos ; tous les autres sont envoyés tels quels. La liste et ses paquets ne doivent plus être modifiés
     * après l'appel : ils sont lus par le fil réseau.
     */
    public static void send(Player viewer, List<PacketWrapper<?>> packets, PacketWrapper<?> visible) {
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(viewer);
            Object channel = user == null ? null : user.getChannel();
            if (channel == null) {
                return;
            }
            ChannelHelper.runInEventLoop(channel, () -> {
                try {
                    for (PacketWrapper<?> packet : packets) {
                        if (packet == visible) {
                            user.writePacket(packet);
                        } else {
                            user.writePacketSilently(packet);
                        }
                    }
                    user.flushPackets();
                } catch (RuntimeException ignored) {
                    // joueur en cours de déconnexion
                }
            });
        } catch (RuntimeException ignored) {
            // joueur en cours de déconnexion, fil réseau arrêté
        }
    }

    public static void send(Player viewer, List<PacketWrapper<?>> packets) {
        send(viewer, packets, null);
    }
}

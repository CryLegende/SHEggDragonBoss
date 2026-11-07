package org.Cry_Legende.sHEggDragonBoss.managers;

import com.bgsoftware.superiorskyblock.api.island.Island;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.boss.BossType;
import org.Cry_Legende.sHEggDragonBoss.boss.PlayerText;
import org.Cry_Legende.sHEggDragonBoss.config.BossSettings;
import org.Cry_Legende.sHEggDragonBoss.config.Messages;
import org.Cry_Legende.sHEggDragonBoss.config.Rewards;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ce qui se passe à la mort d'un boss, une fois pour toutes : le palmarès (titre, classement dans le chat), puis les
 * récompenses. Elles vont à tous les <b>participants</b> (voir {@link Rewards}), pas seulement à celui qui a porté le coup fatal.
 */
public final class RewardManager {

    /** Un joueur qui a assez contribué au combat, avec sa part (0 à 1) de la contribution de tous les participants. */
    private record Participant(Player player, double share) {
    }

    /** Nombre de lignes du classement envoyé dans le chat. */
    private static final int SUMMARY_LINES = 5;

    private RewardManager() {
    }

    /**
     * @param contributions ce que chaque joueur a fait au boss (PV du boss), vide si le boss n'avait plus de contrôleur
     * @param killer        celui qui a porté le coup fatal, ou nul
     * @param audience      les joueurs qui suivaient le combat (titre et classement)
     */
    public static void distribute(SHEggDragonBoss plugin, BossType type, BossSettings settings, Map<UUID, Double> contributions,
                                  double maxHealth, Player killer, Island island, Location loc, Collection<Player> audience) {
        Rewards rewards = settings.rewards();
        List<Participant> participants = participants(rewards, contributions, maxHealth, killer);

        announce(plugin, type, settings, participants, audience);
        if (!participants.isEmpty()) {
            plugin.getLogManager().log(plugin.getMessages().journal("rewards",
                "island", islandName(plugin, island),
                "participants", String.valueOf(participants.size()),
                "mvp", participants.get(0).player().getName(),
                "names", String.join(", ", participants.stream().map(p -> p.player().getName()).toList())));
        }

        for (Participant participant : participants) {
            Player player = participant.player();
            if (rewards.xp() > 0) {
                int xp = (int) Math.round(rewards.xp() * participant.share());
                if (xp > 0) player.giveExp(xp);
            }
            for (String command : rewards.guaranteedCommands()) dispatch(plugin, command, player, island, loc);
            for (int roll = 0; roll < rewards.rolls(); roll++) {
                for (String command : rewards.participantCommands()) dispatch(plugin, command, player, island, loc);
            }
        }
        if (!participants.isEmpty()) {
            for (String command : rewards.mvpCommands()) dispatch(plugin, command, participants.get(0).player(), island, loc);
        }
        for (String command : rewards.commands()) dispatch(plugin, command, killer, island, loc);

        for (Rewards.ItemReward reward : rewards.items()) {
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
    }

    /**
     * Les joueurs en ligne dont la contribution atteint le minimum, du plus au moins contributeur. Sans aucun participant, celui
     * qui a porté le coup fatal en est un à lui seul (le boss ne meurt jamais sans récompense à cause d'un seuil mal réglé).
     */
    private static List<Participant> participants(Rewards rewards, Map<UUID, Double> contributions, double maxHealth, Player killer) {
        double minimum = maxHealth * rewards.minContributionPercent() / 100.0;
        List<Map.Entry<UUID, Double>> ranked = contributions.entrySet().stream()
            .filter(entry -> entry.getValue() >= minimum && entry.getValue() > 0.0)
            .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
            .toList();
        double total = 0.0;
        List<Player> players = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Map.Entry<UUID, Double> entry : ranked) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            players.add(player);
            values.add(entry.getValue());
            total += entry.getValue();
        }
        List<Participant> participants = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) participants.add(new Participant(players.get(i), values.get(i) / total));
        if (participants.isEmpty() && killer != null && killer.isOnline()) participants.add(new Participant(killer, 1.0));
        return participants;
    }

    // ------------------------------------------------------------------ palmarès

    private static void announce(SHEggDragonBoss plugin, BossType type, BossSettings settings, List<Participant> participants,
                                 Collection<Player> audience) {
        Messages messages = plugin.getMessages();
        String base = "bosses." + type.id() + ".";
        String name = settings.name();
        Set<Player> everyone = new LinkedHashSet<>(audience);
        for (Participant participant : participants) everyone.add(participant.player());

        String title = messages.format(base + "death-title", "name", name);
        String subtitle = messages.format(base + "death-subtitle", "name", name);
        for (Player player : everyone) PlayerText.title(player, title, subtitle);

        String header = messages.format(base + "death-summary-header", "name", name);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(SUMMARY_LINES, participants.size()); i++) {
            Participant participant = participants.get(i);
            lines.add(messages.format(base + "death-summary-line",
                "rank", String.valueOf(i + 1),
                "player", participant.player().getName(),
                "percent", String.valueOf(Math.round(participant.share() * 100.0))));
        }
        for (Player player : everyone) {
            if (!header.isEmpty()) player.sendMessage(header);
            for (String line : lines) {
                if (!line.isEmpty()) player.sendMessage(line);
            }
        }
    }

    // ------------------------------------------------------------------ commandes

    /**
     * Lance une commande de récompense par la console. Placeholders : {player} le joueur visé, {island} le nom de l'île (sinon de
     * son propriétaire), {world}, {x}, {y}, {z}. Une commande qui contient {player} est ignorée quand il n'y a pas de joueur.
     */
    private static void dispatch(SHEggDragonBoss plugin, String rawCommand, Player player, Island island, Location loc) {
        if (rawCommand == null || rawCommand.trim().isEmpty()) return;
        if (player == null && rawCommand.contains("{player}")) return;

        String islandDisplay = "";
        if (island != null) {
            if (island.getName() != null && !island.getName().isEmpty()) islandDisplay = island.getName();
            else if (island.getOwner() != null) islandDisplay = island.getOwner().getName();
        }
        String command = rawCommand.trim();
        if (command.startsWith("/")) command = command.substring(1);
        command = command
            .replace("{player}", player != null ? player.getName() : "")
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

    private static String islandName(SHEggDragonBoss plugin, Island island) {
        return (island != null && island.getOwner() != null)
            ? plugin.getMessages().journal("island-of", "owner", island.getOwner().getName())
            : plugin.getMessages().journal("island-unknown");
    }
}

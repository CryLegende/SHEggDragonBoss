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
 * récompenses. Elles vont à celui qui a porté le coup fatal, et à lui seul (voir {@link Rewards}). Le classement ne sert qu'à
 * montrer qui a fait quoi.
 */
public final class RewardManager {

    /** Un joueur du classement, avec sa part (0 à 1) de ce qui a été infligé au boss. */
    private record Ranked(Player player, double share) {
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
                                  Player killer, Island island, Location loc, Collection<Player> audience) {
        Rewards rewards = settings.rewards();
        List<Ranked> ranking = ranking(contributions);
        Player winner = winner(killer, ranking);

        announce(plugin, type, settings, ranking, winner, audience);
        plugin.getLogManager().log(plugin.getMessages().journal("rewards",
            "island", islandName(plugin, island),
            "killer", winner != null ? winner.getName() : plugin.getMessages().journal("killer-unknown"),
            "names", ranking.isEmpty() ? "-" : String.join(", ", ranking.stream().map(r -> r.player().getName()).toList())));

        if (winner != null) {
            // La mort peut être traitée sur le thread du monde : l'XP du joueur et les commandes console passent par le thread principal
            Runnable payout = () -> {
                if (rewards.xp() > 0) winner.giveExp(rewards.xp());
                for (String command : rewards.commands()) dispatch(plugin, command, winner, island, loc);
                for (int roll = 0; roll < rewards.rolls(); roll++) {
                    for (String command : rewards.rollCommands()) dispatch(plugin, command, winner, island, loc);
                }
            };
            if (SHEggDragonBoss.onServerThread()) payout.run();
            else Bukkit.getScheduler().runTask(plugin, payout);
        }

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
     * Celui qui reçoit les récompenses : le tueur. Si le coup fatal ne vient d'aucun joueur encore en ligne (il s'est déconnecté
     * pendant la mort en scène, ou le boss est tombé d'une autre cause), le joueur qui a le plus infligé au boss le remplace, pour
     * que la récompense ne se perde jamais.
     */
    private static Player winner(Player killer, List<Ranked> ranking) {
        if (killer != null && killer.isOnline()) return killer;
        return ranking.isEmpty() ? null : ranking.get(0).player();
    }

    /** Les joueurs en ligne qui ont blessé le boss, du plus au moins contributeur. */
    private static List<Ranked> ranking(Map<UUID, Double> contributions) {
        List<Map.Entry<UUID, Double>> sorted = contributions.entrySet().stream()
            .filter(entry -> entry.getValue() > 0.0)
            .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
            .toList();
        double total = 0.0;
        List<Player> players = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Map.Entry<UUID, Double> entry : sorted) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            players.add(player);
            values.add(entry.getValue());
            total += entry.getValue();
        }
        List<Ranked> ranking = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) ranking.add(new Ranked(players.get(i), values.get(i) / total));
        return ranking;
    }

    // ------------------------------------------------------------------ palmarès

    private static void announce(SHEggDragonBoss plugin, BossType type, BossSettings settings, List<Ranked> ranking,
                                 Player winner, Collection<Player> audience) {
        Messages messages = plugin.getMessages();
        String base = "bosses." + type.id() + ".";
        String name = settings.name();
        Set<Player> everyone = new LinkedHashSet<>(audience);
        for (Ranked ranked : ranking) everyone.add(ranked.player());
        if (winner != null) everyone.add(winner);

        String title = messages.format(base + "death-title", "name", name);
        String subtitle = messages.format(base + "death-subtitle", "name", name);
        for (Player player : everyone) PlayerText.title(player, title, subtitle);

        String header = messages.format(base + "death-summary-header", "name", name);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < Math.min(SUMMARY_LINES, ranking.size()); i++) {
            Ranked ranked = ranking.get(i);
            long percent = Math.round(ranked.share() * 100.0);
            if (percent < 1) continue;
            lines.add(messages.format(base + "death-summary-line",
                "rank", String.valueOf(i + 1),
                "player", ranked.player().getName(),
                "percent", String.valueOf(percent)));
        }
        if (winner != null) lines.add(messages.format(base + "death-killer-line", "player", winner.getName()));
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
        // Mode réseau : une commande visant le joueur s'exécute là où il se trouve, une fois sa sauvegarde de passage
        // terminée (récompense donnée pendant un changement de serveur = perdue ou doublée)
        if (player != null && rawCommand.contains("{player}") && plugin.getNetwork() != null) {
            plugin.getNetwork().runFor(player.getName(), command);
            return;
        }
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

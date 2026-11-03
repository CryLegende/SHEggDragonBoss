package org.Cry_Legende.sHEggDragonBoss.commands;

import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.config.Messages;
import org.Cry_Legende.sHEggDragonBoss.items.CreakingItems;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /sheggdragonboss : recharger la configuration et donner les objets du rituel du Creaking.
 * Utilisée par la console (boîtes, temps de jeu, VoteParty...) comme par un administrateur.
 */
public final class BossCommand implements CommandExecutor, TabCompleter {

    public static final String PERMISSION = "sheggdragonboss.admin";
    /** 36 piles de 64 : un inventaire entier. */
    private static final int MAX_AMOUNT = 2304;
    private static final Map<String, CreakingItems.Kind> ITEMS = Map.of(
        "coeur", CreakingItems.Kind.HEART,
        "cœur", CreakingItems.Kind.HEART,
        "heart", CreakingItems.Kind.HEART,
        "briques", CreakingItems.Kind.BRICKS,
        "brique", CreakingItems.Kind.BRICKS,
        "bricks", CreakingItems.Kind.BRICKS);

    private final SHEggDragonBoss plugin;

    public BossCommand(SHEggDragonBoss plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Messages messages = plugin.getMessages();
        if (!sender.hasPermission(PERMISSION)) {
            messages.send(sender, "commands.no-permission");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            messages.send(sender, "commands.help.header", "label", label);
            messages.send(sender, "commands.help.reload", "label", label);
            messages.send(sender, "commands.help.give", "label", label);
            messages.send(sender, "commands.help.footer", "label", label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "give" -> give(sender, label, args);
            default -> messages.send(sender, "commands.unknown", "label", label);
        }
        return true;
    }

    private void reload(CommandSender sender) {
        try {
            int warnings = plugin.reloadAll();
            plugin.getMessages().send(sender, "commands.reloaded", "warnings", String.valueOf(warnings));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Rechargement impossible : " + e);
            plugin.getMessages().send(sender, "commands.reload-failed", "error", String.valueOf(e.getMessage()));
        }
    }

    private void give(CommandSender sender, String label, String[] args) {
        Messages messages = plugin.getMessages();
        if (args.length < 3) {
            messages.send(sender, "commands.give-usage", "label", label);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            messages.send(sender, "commands.player-not-found", "player", args[1]);
            return;
        }
        CreakingItems.Kind kind = ITEMS.get(args[2].toLowerCase(Locale.ROOT));
        if (kind == null) {
            messages.send(sender, "commands.invalid-item", "item", args[2]);
            return;
        }
        int amount = 1;
        if (args.length >= 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                amount = -1;
            }
            if (amount < 1 || amount > MAX_AMOUNT) {
                messages.send(sender, "commands.invalid-amount", "amount", args[3], "max", String.valueOf(MAX_AMOUNT));
                return;
            }
        }
        ItemStack stack = CreakingItems.create(plugin, kind, amount);
        if (stack == null) {
            messages.send(sender, "commands.ritual-disabled");
            return;
        }
        // addItem découpe les piles ; ce qui ne rentre pas est posé aux pieds du joueur
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            leftover.values().forEach(rest -> target.getWorld().dropItemNaturally(target.getLocation(), rest));
            messages.send(target, "commands.inventory-full");
        }
        messages.send(sender, "commands.give-success", "amount", String.valueOf(amount),
            "item", messages.text("commands.item-names." + kind.id()), "player", target.getName());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (!sender.hasPermission(PERMISSION)) return result;
        if (args.length == 1) {
            result.addAll(List.of("help", "reload", "give"));
        } else if (args[0].equalsIgnoreCase("give")) {
            if (args.length == 2) Bukkit.getOnlinePlayers().forEach(p -> result.add(p.getName()));
            else if (args.length == 3) result.addAll(List.of("coeur", "briques"));
            else if (args.length == 4) result.addAll(List.of("1", "10", "64"));
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        result.removeIf(candidate -> !candidate.toLowerCase(Locale.ROOT).startsWith(typed));
        return result;
    }
}

package org.Cry_Legende.sHEggDragonBoss.commands;

import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.config.Messages;
import org.Cry_Legende.sHEggDragonBoss.items.CreakingItems;
import org.Cry_Legende.sHEggDragonBoss.managers.BossManager;
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
 * /sheggdragonboss (alias /shboss) : recharger la configuration, donner les objets du rituel du Creaking et lister
 * les boss et rituels en cours. Utilisée par la console (boîtes, temps de jeu, VoteParty...) comme par un administrateur.
 */
public final class BossCommand implements CommandExecutor, TabCompleter {

    public static final String RELOAD_PERMISSION = "sheggdragonboss.admin.reload";
    public static final String GIVE_PERMISSION = "sheggdragonboss.admin.give";
    public static final String LIST_PERMISSION = "sheggdragonboss.admin.list";
    /** Nom affiché dans les messages : jamais l'alias tapé. */
    private static final String CANONICAL = "sheggdragonboss";
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
        List<String> allowed = allowed(sender);
        if (allowed.isEmpty()) {
            messages.send(sender, "commands.no-permission");
            return true;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload") && allowed.contains("reload")) {
            reload(sender);
        } else if (sub.equals("give") && allowed.contains("give")) {
            give(sender, CANONICAL, args);
        } else if (sub.equals("list") && allowed.contains("list")) {
            list(sender);
        } else {
            messages.send(sender, "commands.usage", "subs", String.join("|", allowed));
        }
        return true;
    }

    /** Sous-commandes que l'expéditeur a le droit d'utiliser, dans l'ordre d'affichage. */
    private static List<String> allowed(CommandSender sender) {
        List<String> subs = new ArrayList<>();
        if (sender.hasPermission(RELOAD_PERMISSION)) subs.add("reload");
        if (sender.hasPermission(GIVE_PERMISSION)) subs.add("give");
        if (sender.hasPermission(LIST_PERMISSION)) subs.add("list");
        return subs;
    }

    private void reload(CommandSender sender) {
        try {
            int warnings = plugin.reloadAll();
            plugin.getMessages().send(sender, "commands.reloaded", "warnings", String.valueOf(warnings));
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Rechargement impossible, ancienne configuration gardée : " + e.getMessage());
            plugin.getMessages().send(sender, "commands.reload-failed", "error", String.valueOf(e.getMessage()));
        }
    }

    /** Boss actifs, boss dormants et rituels en cours. Lecture seule. */
    private void list(CommandSender sender) {
        Messages messages = plugin.getMessages();
        BossManager manager = plugin.getBossManager();
        List<BossManager.BossLine> bosses = manager == null ? List.of() : manager.bossLines();
        List<BossManager.RitualLine> rituals = manager == null ? List.of() : manager.ritualLines();
        if (bosses.isEmpty() && rituals.isEmpty()) {
            messages.send(sender, "commands.list.none");
            return;
        }
        messages.send(sender, "commands.list.header", "bosses", String.valueOf(bosses.size()), "rituals", String.valueOf(rituals.size()));
        for (BossManager.BossLine boss : bosses) {
            messages.send(sender, "commands.list.boss",
                "type", boss.type().id(), "owner", boss.owner(),
                "state", messages.text(boss.active() ? "commands.list.state-active" : "commands.list.state-dormant"),
                "world", boss.world(), "x", String.valueOf(boss.x()), "y", String.valueOf(boss.y()), "z", String.valueOf(boss.z()),
                "health", String.format(Locale.ROOT, "%.0f", boss.health()), "max", String.format(Locale.ROOT, "%.0f", boss.maxHealth()),
                "phase", String.valueOf(boss.phase()));
        }
        for (BossManager.RitualLine ritual : rituals) {
            messages.send(sender, "commands.list.ritual",
                "type", ritual.type().id(),
                "state", messages.text(ritual.countdown() ? "commands.list.state-countdown" : "commands.list.state-waiting"),
                "world", ritual.world(), "x", String.valueOf(ritual.x()), "y", String.valueOf(ritual.y()), "z", String.valueOf(ritual.z()),
                "items", String.valueOf(ritual.items()), "required", String.valueOf(ritual.required()));
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
        List<String> allowed = allowed(sender);
        if (args.length == 1) {
            result.addAll(allowed);
        } else if (args[0].equalsIgnoreCase("give") && allowed.contains("give")) {
            if (args.length == 2) Bukkit.getOnlinePlayers().forEach(p -> result.add(p.getName()));
            else if (args.length == 3) result.addAll(List.of("coeur", "briques"));
            else if (args.length == 4) result.addAll(List.of("1", "10", "64"));
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        result.removeIf(candidate -> !candidate.toLowerCase(Locale.ROOT).startsWith(typed));
        return result;
    }
}

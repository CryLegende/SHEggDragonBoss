package org.Cry_Legende.sHEggDragonBoss.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.Cry_Legende.sHEggDragonBoss.boss.BossType;
import org.Cry_Legende.sHEggDragonBoss.config.Messages;
import org.Cry_Legende.sHEggDragonBoss.config.RitualSettings;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Les deux objets du rituel du Creaking : le cœur (le bloc à poser) et les briques de résine (à donner au cœur).
 * Ce sont des objets ordinaires pour le rituel (seul le type compte) ; le lore, lu dans messages.yml, explique à quoi ils servent.
 */
public final class CreakingItems {

    public enum Kind {
        HEART("heart"),
        BRICKS("bricks");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        /** Clé dans messages.yml : commands.item-names.&lt;id&gt;. */
        public String id() {
            return id;
        }
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private CreakingItems() {
    }

    /** Un objet du rituel, ou null si le rituel du Creaking est désactivé ou inutilisable. */
    public static ItemStack create(SHEggDragonBoss plugin, Kind kind, int amount) {
        RitualSettings ritual = plugin.getConfigManager().ritual(BossType.CREAKING);
        if (ritual == null || !ritual.enabled()) return null;
        Material material = kind == Kind.HEART ? ritual.block() : ritual.item();
        ItemStack stack = new ItemStack(material, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) return stack;
        String path = kind == Kind.HEART ? "items.creaking-heart.lore" : "items.resin-brick.lore";
        List<Component> lore = plugin.getMessages().lines(path).stream()
            .<Component>map(line -> LEGACY.deserialize(Messages.apply(line, "required", String.valueOf(ritual.requiredAmount())))
                .decoration(TextDecoration.ITALIC, false))
            .toList();
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }
}

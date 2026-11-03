package org.Cry_Legende.sHEggDragonBoss.boss;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * Titres et barre d'action à partir des textes de la configuration (codes couleur déjà traduits en « § »).
 * Un texte vide n'est pas envoyé.
 */
public final class PlayerText {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final Title.Times TIMES = Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2600), Duration.ofMillis(700));

    private PlayerText() {
    }

    public static void actionBar(Player player, String text) {
        if (text == null || text.isEmpty()) return;
        player.sendActionBar(LEGACY.deserialize(text));
    }

    public static void title(Player player, String title, String subtitle) {
        boolean noTitle = title == null || title.isEmpty();
        boolean noSubtitle = subtitle == null || subtitle.isEmpty();
        if (noTitle && noSubtitle) return;
        Component main = noTitle ? Component.empty() : LEGACY.deserialize(title);
        Component sub = noSubtitle ? Component.empty() : LEGACY.deserialize(subtitle);
        player.showTitle(Title.title(main, sub, TIMES));
    }
}

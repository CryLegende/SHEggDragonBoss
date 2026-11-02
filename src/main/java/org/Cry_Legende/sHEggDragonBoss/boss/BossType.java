package org.Cry_Legende.sHEggDragonBoss.boss;

import org.Cry_Legende.sHEggDragonBoss.SHEggDragonBoss;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Les deux boss du plugin. L'identifiant sert dans config.yml, dans les fichiers de données et sur l'entité.
 */
public enum BossType {
    DRAGON("dragon"),
    CREAKING("creaking");

    private final String id;

    BossType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** Type correspondant à un identifiant, ou null s'il est vide ou inconnu. */
    public static BossType fromId(String id) {
        if (id == null) return null;
        for (BossType type : values()) {
            if (type.id.equalsIgnoreCase(id.trim())) return type;
        }
        return null;
    }

    /**
     * Type d'un boss d'après son conteneur de données. L'absence de clé signifie « dragon » : les boss créés
     * avant l'arrivée du second boss n'ont pas ce champ. Renvoie null si l'identifiant écrit est inconnu.
     */
    public static BossType of(PersistentDataContainer container) {
        String raw = container.get(SHEggDragonBoss.BOSS_TYPE_KEY, PersistentDataType.STRING);
        return raw == null ? DRAGON : fromId(raw);
    }
}

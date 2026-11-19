package org.Cry_Legende.sHEggDragonBoss.model;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Une entité d'affichage d'un modèle, prête à envoyer : type, transformation dans le repère du modèle et toutes les
 * métadonnées fixes déjà converties (objet, bloc, texte, panneau, luminosité...). Les métadonnées sont partagées
 * par tous les paquets.
 */
public final class Part {

    // Indices des métadonnées des entités d'affichage (Minecraft 1.20.2 à 26.3)
    public static final int FLAGS = 0;
    public static final int INTERPOLATION_DELAY = 8;
    public static final int TRANSFORMATION_DURATION = 9;
    public static final int POS_ROT_DURATION = 10;
    public static final int TRANSLATION = 11;
    public static final int SCALE = 12;
    public static final int LEFT_ROTATION = 13;
    public static final int RIGHT_ROTATION = 14;
    public static final int BILLBOARD = 15;
    private static final int BRIGHTNESS = 16;
    private static final int VIEW_RANGE = 17;
    private static final int SHADOW_RADIUS = 18;
    private static final int SHADOW_STRENGTH = 19;
    private static final int WIDTH = 20;
    private static final int HEIGHT = 21;
    private static final int GLOW_COLOR = 22;
    public static final int ITEM = 23;
    private static final int ITEM_CONTEXT = 24;
    private static final int BLOCK_STATE = 23;
    public static final int TEXT = 23;
    private static final int LINE_WIDTH = 24;
    private static final int BACKGROUND = 25;
    private static final int TEXT_OPACITY = 26;
    private static final int TEXT_FLAGS = 27;

    /** Contexte d'affichage « fixed » : la tête est centrée sur l'origine de l'entité. */
    public static final byte CONTEXT_FIXED = 8;

    private static final List<String> CONTEXTS = List.of("none", "thirdperson_lefthand", "thirdperson_righthand",
        "firstperson_lefthand", "firstperson_righthand", "head", "gui", "ground", "fixed", "on_shelf");
    private static final List<String> BILLBOARDS = List.of("fixed", "vertical", "horizontal", "center");

    // Têtes déjà construites, par texture réduite à l'adresse de son image
    private static final Map<String, com.github.retrooper.packetevents.protocol.item.ItemStack> HEADS = new java.util.concurrent.ConcurrentHashMap<>();

    private final EntityType type;
    private final Affine transform;
    private final List<EntityData<?>> staticData;
    private final Track track;
    private final Looks looks;
    private final Track[] states;

    private Part(EntityType type, Affine transform, List<EntityData<?>> staticData, Track track, Looks looks) {
        this(type, transform, Collections.unmodifiableList(staticData), track, looks, null);
    }

    private Part(EntityType type, Affine transform, List<EntityData<?>> staticData, Track track, Looks looks, Track[] states) {
        this.type = type;
        this.transform = transform;
        this.staticData = staticData;
        this.track = track;
        this.looks = looks;
        this.states = states;
    }

    public EntityType type() {
        return type;
    }

    public Affine transform() {
        return transform;
    }

    /**
     * Métadonnées fixes (tout sauf la transformation et ses durées d'interpolation). La première est l'apparence de
     * la pièce (objet, bloc ou texte), la seule qu'une animation peut remplacer.
     */
    public List<EntityData<?>> staticData() {
        return staticData;
    }

    /** Mouvement propre à la pièce (images clés du modèle), null si elle garde sa pose. */
    public Track track() {
        return track;
    }

    /** Apparences successives de la pièce, null si elle garde la sienne. */
    public Looks looks() {
        return looks;
    }

    /**
     * Mouvement de la pièce dans un état du modèle (numéro de l'état dans le modèle), null si elle y garde sa pose
     * de base ou si le modèle n'a pas d'états.
     */
    public Track stateTrack(int state) {
        return states == null || state < 0 ? null : states[state];
    }

    /** Cette pièce avec un mouvement. */
    public Part withTrack(Track track) {
        return new Part(type, transform, staticData, track, looks, states);
    }

    /** Cette pièce avec un cycle d'apparences, dont la première est sa première métadonnée. */
    public Part withLooks(Looks looks) {
        return new Part(type, transform, staticData, track, looks, states);
    }

    /** Cette pièce avec son mouvement dans chaque état du modèle (null là où elle ne bouge pas). */
    public Part withStates(Track[] states) {
        return new Part(type, transform, staticData, track, looks, states);
    }

    /** Apparence d'une tête de joueur, pour un cycle de textures. */
    public static EntityData<?> headLook(com.github.retrooper.packetevents.protocol.item.ItemStack item) {
        return new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item);
    }

    /** Tête de joueur seule : contexte « fixed », centrée, 0,5 bloc de côté à l'échelle 1. */
    public static Part head(com.github.retrooper.packetevents.protocol.item.ItemStack item) {
        List<EntityData<?>> data = new ArrayList<>();
        data.add(new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, item));
        data.add(new EntityData<>(ITEM_CONTEXT, EntityDataTypes.BYTE, CONTEXT_FIXED));
        return new Part(EntityTypes.ITEM_DISPLAY, Affine.IDENTITY, data, null, null);
    }

    /**
     * Apparence d'une entité d'affichage lue : l'objet d'un item_display, le bloc d'un block_display, le texte d'un
     * text_display. C'est la métadonnée qu'une animation remplace pour changer de texture.
     */
    public static EntityData<?> look(SummonImporter.RawPart raw) {
        Snbt.Compound entity = raw.data();
        return switch (raw.type()) {
            case "block_display" -> new EntityData<>(BLOCK_STATE, EntityDataTypes.BLOCK_STATE,
                SpigotConversionUtil.fromBukkitBlockData(blockData(entity.compound("block_state"))).getGlobalId());
            case "item_display" -> {
                // Tête de joueur à texture, le cas courant : construite sans repasser par l'analyse de l'objet, et
                // partagée par toutes les pièces et toutes les poses qui montrent la même texture
                String texture = headTexture(entity.compound("item"));
                yield new EntityData<>(ITEM, EntityDataTypes.ITEMSTACK, texture != null
                    ? HEADS.computeIfAbsent(compactTexture(texture), t -> SpigotConversionUtil.fromBukkitItemStack(head(t)))
                    : SpigotConversionUtil.fromBukkitItemStack(item(entity.compound("item"))));
            }
            default -> new EntityData<>(TEXT, EntityDataTypes.ADV_COMPONENT, text(entity.get("text")));
        };
    }

    /** Texte d'origine de l'apparence d'une entité lue : égal pour deux poses qui montrent la même chose. */
    public static String lookId(SummonImporter.RawPart raw) {
        String key = switch (raw.type()) {
            case "block_display" -> "block_state";
            case "item_display" -> "item";
            default -> "text";
        };
        return String.valueOf(raw.data().raw(key));
    }

    /** Pièce d'un modèle importé. */
    public static Part of(SummonImporter.RawPart raw) {
        Snbt.Compound entity = raw.data();
        List<EntityData<?>> data = new ArrayList<>();
        data.add(look(raw));
        EntityType type;
        switch (raw.type()) {
            case "block_display" -> type = EntityTypes.BLOCK_DISPLAY;
            case "item_display" -> {
                type = EntityTypes.ITEM_DISPLAY;
                String context = entity.string("item_display");
                int index = context == null ? 0 : CONTEXTS.indexOf(context.toLowerCase(Locale.ROOT));
                if (index > 0) {
                    data.add(new EntityData<>(ITEM_CONTEXT, EntityDataTypes.BYTE, (byte) index));
                }
            }
            default -> {
                type = EntityTypes.TEXT_DISPLAY;
                Number lineWidth = entity.number("line_width");
                if (lineWidth != null) {
                    data.add(new EntityData<>(LINE_WIDTH, EntityDataTypes.INT, lineWidth.intValue()));
                }
                Number background = entity.number("background");
                if (background != null) {
                    data.add(new EntityData<>(BACKGROUND, EntityDataTypes.INT, background.intValue()));
                }
                Number opacity = entity.number("text_opacity");
                if (opacity != null) {
                    data.add(new EntityData<>(TEXT_OPACITY, EntityDataTypes.BYTE, opacity.byteValue()));
                }
                byte flags = 0;
                if (entity.flag("shadow")) {
                    flags |= 0x01;
                }
                if (entity.flag("see_through")) {
                    flags |= 0x02;
                }
                if (entity.flag("default_background")) {
                    flags |= 0x04;
                }
                String alignment = entity.string("alignment");
                if ("left".equals(alignment)) {
                    flags |= 0x08;
                } else if ("right".equals(alignment)) {
                    flags |= 0x10;
                }
                if (flags != 0) {
                    data.add(new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, flags));
                }
            }
        }

        if (entity.flag("Glowing")) {
            data.add(new EntityData<>(FLAGS, EntityDataTypes.BYTE, (byte) 0x40));
        }
        String billboard = entity.string("billboard");
        int billboardIndex = billboard == null ? 0 : BILLBOARDS.indexOf(billboard.toLowerCase(Locale.ROOT));
        if (billboardIndex > 0) {
            data.add(new EntityData<>(BILLBOARD, EntityDataTypes.BYTE, (byte) billboardIndex));
        }
        Snbt.Compound brightness = entity.compound("brightness");
        if (brightness != null) {
            int sky = brightness.number("sky") == null ? 0 : brightness.number("sky").intValue();
            int block = brightness.number("block") == null ? 0 : brightness.number("block").intValue();
            data.add(new EntityData<>(BRIGHTNESS, EntityDataTypes.INT, block << 4 | sky << 20));
        }
        addFloat(data, VIEW_RANGE, entity.number("view_range"));
        addFloat(data, SHADOW_RADIUS, entity.number("shadow_radius"));
        addFloat(data, SHADOW_STRENGTH, entity.number("shadow_strength"));
        addFloat(data, WIDTH, entity.number("width"));
        addFloat(data, HEIGHT, entity.number("height"));
        Number glow = entity.number("glow_color_override");
        if (glow != null) {
            data.add(new EntityData<>(GLOW_COLOR, EntityDataTypes.INT, glow.intValue()));
        }
        return new Part(type, raw.transform(), data, null, null);
    }

    private static void addFloat(List<EntityData<?>> data, int index, Number value) {
        if (value != null) {
            data.add(new EntityData<>(index, EntityDataTypes.FLOAT, value.floatValue()));
        }
    }

    private static BlockData blockData(Snbt.Compound state) {
        StringBuilder text = new StringBuilder(SummonImporter.blockName(state));
        Snbt.Compound properties = SummonImporter.blockProperties(state);
        if (properties != null && !properties.isEmpty()) {
            text.append('[');
            boolean first = true;
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (!first) {
                    text.append(',');
                }
                text.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
            text.append(']');
        }
        return Bukkit.createBlockData(text.toString());
    }

    /**
     * Texture d'une tête de joueur décrite en SNBT (composant « minecraft:profile », ou ancien « SkullOwner »), null
     * pour tout autre objet ou une tête sans texture.
     */
    private static String headTexture(Snbt.Compound item) {
        String id = item == null ? null : item.string("id");
        if (id == null || !id.endsWith("player_head")) {
            return null;
        }
        Snbt.Compound components = item.compound("components");
        Snbt.Compound profile = components == null ? null : components.compound("minecraft:profile");
        List<?> properties = profile == null ? null : profile.list("properties");
        if (properties != null) {
            for (Object property : properties) {
                if (property instanceof Snbt.Compound c && "textures".equals(c.string("name")) && c.string("value") != null) {
                    return c.string("value");
                }
            }
        }
        Snbt.Compound tag = item.compound("tag");
        Snbt.Compound owner = tag == null ? null : tag.compound("SkullOwner");
        Snbt.Compound legacy = owner == null ? null : owner.compound("Properties");
        List<?> textures = legacy == null ? null : legacy.list("textures");
        if (textures != null && !textures.isEmpty() && textures.getFirst() instanceof Snbt.Compound texture) {
            return texture.string("Value");
        }
        return null;
    }

    /**
     * Objet décrit en SNBT : composants actuels (« components », repassés tels quels au jeu comme dans /give) ou
     * ancien format d'avant la 1.20.5 (« tag » avec SkullOwner pour les têtes).
     */
    private static ItemStack item(Snbt.Compound item) {
        String id = item.string("id");
        Snbt.Compound components = item.compound("components");
        if (components != null && !components.isEmpty()) {
            StringBuilder text = new StringBuilder(id).append('[');
            boolean first = true;
            for (String key : components.keySet()) {
                if (!first) {
                    text.append(',');
                }
                text.append(key).append('=').append(components.raw(key));
                first = false;
            }
            text.append(']');
            ItemStack stack = Bukkit.getItemFactory().createItemStack(text.toString());
            if (stack.getType() == Material.PLAYER_HEAD && stack.getItemMeta() instanceof SkullMeta meta && meta.getPlayerProfile() != null) {
                for (ProfileProperty property : meta.getPlayerProfile().getProperties()) {
                    if (property.getName().equals("textures")) {
                        return head(property.getValue());
                    }
                }
            }
            return stack;
        }
        Snbt.Compound tag = item.compound("tag");
        Snbt.Compound owner = tag == null ? null : tag.compound("SkullOwner");
        if (owner != null && id.endsWith("player_head")) {
            Snbt.Compound properties = owner.compound("Properties");
            List<?> textures = properties == null ? null : properties.list("textures");
            if (textures != null && !textures.isEmpty() && textures.getFirst() instanceof Snbt.Compound texture) {
                String value = texture.string("Value");
                if (value != null) {
                    return head(value);
                }
            }
        }
        Material material = Material.matchMaterial(id);
        return new ItemStack(material == null ? Material.BARRIER : material);
    }

    /** Tête de joueur portant cette texture (valeur base64 de la propriété « textures »). */
    public static ItemStack head(String texture) {
        texture = compactTexture(texture);
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        PlayerProfile profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8)), "SHEggDragonBoss");
        profile.getProperties().add(new ProfileProperty("textures", texture));
        meta.setPlayerProfile(profile);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Texture réduite à l'adresse de l'image. Les têtes exportées par block-display.com portent aussi l'horodatage,
     * le profil d'origine et parfois le modèle de bras : trois fois plus d'octets envoyés à chaque joueur qui voit
     * le modèle, pour une image identique. Une valeur illisible est gardée telle quelle.
     */
    static String compactTexture(String value) {
        try {
            String json = new String(Base64.getMimeDecoder().decode(value.trim()), StandardCharsets.UTF_8);
            int host = json.indexOf("textures.minecraft.net/texture/");
            int start = host < 0 ? -1 : json.lastIndexOf('"', host);
            int end = host < 0 ? -1 : json.indexOf('"', host);
            if (start < 0 || end < 0) {
                return value;
            }
            String compact = "{\"textures\":{\"SKIN\":{\"url\":\"" + json.substring(start + 1, end) + "\"}}}";
            return Base64.getEncoder().encodeToString(compact.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    /** Texte d'un text_display : JSON (entre guillemets dans la commande) ou composant SNBT (1.21.5 et plus). */
    private static Component text(Object value) {
        if (value instanceof String json) {
            return GsonComponentSerializer.gson().deserialize(json);
        }
        return GsonComponentSerializer.gson().deserialize(toJson(value));
    }

    private static final java.util.Set<String> STYLE_FLAGS = java.util.Set.of("bold", "italic", "underlined", "strikethrough", "obfuscated");

    private static String toJson(Object value) {
        if (value instanceof Snbt.Compound compound) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : compound.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                out.append(quote(entry.getKey())).append(':');
                Object v = entry.getValue();
                if (STYLE_FLAGS.contains(entry.getKey()) && v instanceof Number n) {
                    out.append(n.intValue() != 0);
                } else {
                    out.append(toJson(v));
                }
                first = false;
            }
            return out.append('}').toString();
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(toJson(list.get(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return quote(String.valueOf(value));
    }

    private static String quote(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + '"';
    }
}

package org.Cry_Legende.sHEggDragonBoss.model;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lit les commandes /summon exportées par block-display.com (BDEngine) : entité racine, ses passagers (récursifs),
 * leurs transformations (16 nombres ou translation/rotations/échelle). Ne touche à rien du serveur : le résultat
 * est une liste d'entités d'affichage dont l'origine commune est celle de la première commande.
 */
public final class SummonImporter {

    /** Entité d'affichage lue : type (item_display, block_display, text_display), transformation, données brutes. */
    public record RawPart(String type, Affine transform, Snbt.Compound data) {
    }

    /** Boîte englobante d'un modèle (blocs). */
    public record Bounds(Vector3f min, Vector3f max) {
        public Vector3f size() {
            return new Vector3f(max).sub(min);
        }

        public Vector3f center() {
            return new Vector3f(min).add(max).mul(0.5f);
        }
    }

    private SummonImporter() {
    }

    /** Pose d'une animation : le modèle entier à cet instant du cycle (ticks), en commandes /summon. */
    public record Pose(int tick, List<String> commands) {
    }

    /** Animation d'un modèle : durée du cycle (ticks) et poses successives, après la pose de base à l'instant 0. */
    public record Clip(int loop, List<Pose> poses) {
    }

    /** Image clé d'une pièce dans un état : instant (ticks) et transformation, dans le repère des commandes. */
    public record Key(int tick, Affine transform) {
    }

    /**
     * État d'un modèle fait dans Blockbench (« idle », « walk », « interact »...) : une animation de toutes ses
     * pièces, jouée en boucle ou une seule fois. tracks : images clés de chaque pièce qui bouge, par numéro de pièce
     * (ordre des entités de la pose de base, à partir de 0).
     */
    public record State(String name, int length, boolean once, java.util.Map<Integer, List<Key>> tracks) {
    }

    /** Contenu d'un fichier de modèle : commandes de la pose de base, puis animations et états éventuels. */
    public record Source(List<String> base, List<Clip> clips, List<State> states) {
    }

    /** Commandes de la pose de base d'un fichier de modèle (les animations sont ignorées). */
    public static List<String> splitCommands(String content) {
        return split(content).base();
    }

    /**
     * Découpe un fichier de modèle. Chaque ligne qui commence par summon ou /summon ouvre une commande, les lignes
     * suivantes (hors commentaires #) la prolongent. Toute autre commande (/kill de la fenêtre d'export de
     * block-display.com, etc.) termine la commande en cours et est ignorée. Les commandes placées avant la première
     * ligne « @animation » forment la pose de base. « @animation loop=<ticks> » ouvre une animation, « @pose <tick> »
     * une pose de cette animation : les commandes qui suivent décrivent le modèle entier à cet instant.
     * « @state <nom> length=<ticks> [once] » ouvre un état, « @track <pièce> <tick>:<10 nombres> ... » donne les
     * images clés d'une pièce dans cet état (translation, rotation gauche, échelle).
     */
    public static Source split(String content) {
        List<String> base = new ArrayList<>();
        List<Clip> clips = new ArrayList<>();
        List<State> states = new ArrayList<>();
        List<String> target = base;
        StringBuilder current = null;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String lower = trimmed.toLowerCase(Locale.ROOT);
            if (lower.startsWith("@")) {
                if (current != null) {
                    target.add(current.toString());
                    current = null;
                }
                String[] words = lower.split("[\\s=]+");
                if (words[0].equals("@animation")) {
                    clips.add(new Clip(directiveNumber(words, "loop", trimmed), new ArrayList<>()));
                    target = null;
                } else if (words[0].equals("@pose")) {
                    if (clips.isEmpty()) {
                        throw new IllegalArgumentException("« " + trimmed + " » avant toute ligne @animation");
                    }
                    Pose pose = new Pose(directiveNumber(words, null, trimmed), new ArrayList<>());
                    clips.getLast().poses().add(pose);
                    target = pose.commands();
                } else if (words[0].equals("@state")) {
                    if (words.length < 2 || words[1].equals("length")) {
                        throw new IllegalArgumentException("« " + trimmed + " » : nom de l'état attendu");
                    }
                    states.add(new State(words[1], directiveNumber(words, "length", trimmed),
                        java.util.Arrays.asList(words).contains("once"), new java.util.TreeMap<>()));
                    target = null;
                } else if (words[0].equals("@track")) {
                    if (states.isEmpty()) {
                        throw new IllegalArgumentException("« @track » avant toute ligne @state");
                    }
                    readTrack(lower, states.getLast());
                } else {
                    throw new IllegalArgumentException("ligne inconnue « " + trimmed + " » (attendu : @animation, @pose, @state ou @track)");
                }
                continue;
            }
            boolean summon = lower.startsWith("summon ") || lower.startsWith("/summon ") || lower.startsWith("minecraft:summon ");
            if (summon || isOtherCommand(lower)) {
                if (current != null) {
                    target.add(current.toString());
                }
                if (summon && target == null) {
                    throw new IllegalArgumentException("commande /summon après @animation sans ligne @pose");
                }
                current = summon ? new StringBuilder(trimmed) : null;
            } else if (current != null) {
                current.append(' ').append(trimmed);
            }
        }
        if (current != null) {
            target.add(current.toString());
        }
        return new Source(base, clips, states);
    }

    /** Ligne « @track <pièce> <tick>:<tx,ty,tz,gx,gy,gz,gw,ex,ey,ez> ... » : images clés d'une pièce dans un état. */
    private static void readTrack(String line, State state) {
        String[] words = line.split("\\s+");
        try {
            int part = Integer.parseInt(words[1]);
            List<Key> keys = new ArrayList<>(words.length - 2);
            for (int w = 2; w < words.length; w++) {
                int colon = words[w].indexOf(':');
                int tick = Integer.parseInt(words[w].substring(0, colon));
                String[] numbers = words[w].substring(colon + 1).split(",");
                if (numbers.length != 10) {
                    throw new NumberFormatException();
                }
                float[] v = new float[10];
                for (int i = 0; i < 10; i++) {
                    v[i] = Float.parseFloat(numbers[i]);
                }
                if (tick >= state.length() || (keys.isEmpty() ? tick != 0 : tick <= keys.getLast().tick())) {
                    throw new IllegalArgumentException("@track " + part + " (état " + state.name()
                        + ") : instants croissants attendus, de 0 à " + (state.length() - 1));
                }
                keys.add(new Key(tick, new Affine(new Vector3f(v[0], v[1], v[2]), new Quaternionf(v[3], v[4], v[5], v[6]).normalize(),
                    new Vector3f(v[7], v[8], v[9]), new Quaternionf())));
            }
            if (keys.isEmpty() || state.tracks().put(part, keys) != null) {
                throw new IllegalArgumentException("@track " + part + " (état " + state.name() + ") : vide ou donné deux fois");
            }
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("ligne @track illisible dans l'état " + state.name()
                + " (attendu : @track <pièce> <tick>:<10 nombres séparés par des virgules> ...)");
        }
    }

    /**
     * Pistes d'un état, une par pièce (null là où la pièce garde sa pose de base), recentrées comme les pièces
     * elles-mêmes. base : pièces de la pose de base déjà recentrées.
     */
    public static Track[] stateTracks(State state, List<RawPart> base, Bounds bounds) {
        Vector3fc shift = bounds.center().negate();
        Track[] tracks = new Track[base.size()];
        for (java.util.Map.Entry<Integer, List<Key>> entry : state.tracks().entrySet()) {
            int part = entry.getKey();
            if (part < 0 || part >= base.size()) {
                throw new IllegalArgumentException("@track " + part + " (état " + state.name() + ") : le modèle n'a que " + base.size() + " pièces");
            }
            List<Key> keys = entry.getValue();
            int[] ticks = new int[keys.size()];
            Affine[] poses = new Affine[keys.size()];
            for (int k = 0; k < ticks.length; k++) {
                ticks[k] = keys.get(k).tick();
                poses[k] = keys.get(k).transform().shifted(shift);
            }
            tracks[part] = Track.ofState(state.length(), ticks, poses, base.get(part).transform());
        }
        return tracks;
    }

    /** Nombre d'une ligne @ : « @pose 10 », « @animation loop=40 » ou « @animation loop 40 ». */
    private static int directiveNumber(String[] words, String name, String line) {
        int index = name == null ? 1 : java.util.Arrays.asList(words).indexOf(name) + 1;
        try {
            if (index < 1 || index >= words.length) {
                throw new NumberFormatException();
            }
            int value = Integer.parseInt(words[index]);
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("« " + line + " » : nombre de ticks positif attendu");
        }
    }

    // Ligne qui commence une autre commande que summon (avec /, ou sans / comme dans un .mcfunction)
    private static final java.util.regex.Pattern OTHER_COMMAND = java.util.regex.Pattern.compile(
        "^(kill|execute|tp|teleport|data|function|tag|scoreboard|ride|schedule|setblock|fill|give|say|tellraw|playsound|particle|effect|return)\\s");

    private static boolean isOtherCommand(String lower) {
        return lower.startsWith("/") || OTHER_COMMAND.matcher(lower).find();
    }

    public static List<RawPart> parse(List<String> commands) {
        List<RawPart> parts = new ArrayList<>();
        double[] origin = null;
        for (String command : commands) {
            Parsed parsed = parseCommand(command);
            if (origin == null) {
                origin = parsed.position;
            }
            Vector3f offset = new Vector3f(
                (float) (parsed.position[0] - (parsed.relative[0] ? 0 : origin[0])),
                (float) (parsed.position[1] - (parsed.relative[1] ? 0 : origin[1])),
                (float) (parsed.position[2] - (parsed.relative[2] ? 0 : origin[2])));
            walk(parsed.root, offset, parts, 0);
        }
        return parts;
    }

    private record Parsed(Snbt.Compound root, double[] position, boolean[] relative) {
    }

    private static Parsed parseCommand(String command) {
        String text = command.trim();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        int[] cursor = {0};
        String keyword = token(text, cursor);
        if (!keyword.equalsIgnoreCase("summon") && !keyword.equalsIgnoreCase("minecraft:summon")) {
            throw new IllegalArgumentException("commande /summon attendue");
        }
        String entity = token(text, cursor);
        double[] position = new double[3];
        boolean[] relative = new boolean[3];
        for (int i = 0; i < 3; i++) {
            skipSpaces(text, cursor);
            if (cursor[0] >= text.length() || text.charAt(cursor[0]) == '{') {
                relative[i] = true;
                continue;
            }
            String coordinate = token(text, cursor);
            if (coordinate.startsWith("~") || coordinate.startsWith("^")) {
                relative[i] = true;
                position[i] = coordinate.length() > 1 ? Double.parseDouble(coordinate.substring(1)) : 0.0;
            } else {
                position[i] = Double.parseDouble(coordinate);
            }
        }
        skipSpaces(text, cursor);
        Snbt.Compound root;
        if (cursor[0] < text.length()) {
            Object value = Snbt.read(text, cursor[0]).value();
            if (!(value instanceof Snbt.Compound compound)) {
                throw new IllegalArgumentException("données de l'entité attendues entre { }");
            }
            root = compound;
        } else {
            root = new Snbt.Compound();
        }
        root.putIfAbsent("id", entity);
        return new Parsed(root, position, relative);
    }

    private static void walk(Snbt.Compound entity, Vector3f offset, List<RawPart> out, int depth) {
        if (depth > 32) {
            throw new IllegalArgumentException("passagers imbriqués trop profondément");
        }
        String type = entityType(entity.string("id"));
        if (type != null && renders(type, entity)) {
            out.add(new RawPart(type, transformation(entity).shifted(offset), entity));
        }
        List<?> passengers = entity.list("Passengers");
        if (passengers != null) {
            for (Object passenger : passengers) {
                if (passenger instanceof Snbt.Compound compound) {
                    walk(compound, offset, out, depth + 1);
                }
            }
        }
    }

    private static String entityType(String id) {
        if (id == null) {
            return null;
        }
        String name = id.toLowerCase(Locale.ROOT);
        if (name.startsWith("minecraft:")) {
            name = name.substring("minecraft:".length());
        }
        return switch (name) {
            case "item_display", "block_display", "text_display" -> name;
            default -> null;
        };
    }

    private static boolean renders(String type, Snbt.Compound entity) {
        switch (type) {
            case "block_display": {
                String name = blockName(entity.compound("block_state"));
                return name != null && !name.endsWith("air");
            }
            case "item_display": {
                Snbt.Compound item = entity.compound("item");
                String id = item == null ? null : item.string("id");
                return id != null && !id.endsWith("air");
            }
            default:
                return entity.containsKey("text");
        }
    }

    /**
     * Nom du bloc d'un block_state : clé « Name » jusqu'à la 26.2, « id » dans les commandes exportées pour la 26.3.
     */
    public static String blockName(Snbt.Compound state) {
        if (state == null) {
            return null;
        }
        String name = state.string("Name");
        return name != null ? name : state.string("id");
    }

    /** Propriétés d'un block_state : « Properties » ou « properties » selon la version de la commande. */
    public static Snbt.Compound blockProperties(Snbt.Compound state) {
        Snbt.Compound properties = state.compound("Properties");
        return properties != null ? properties : state.compound("properties");
    }

    static Affine transformation(Snbt.Compound entity) {
        Object value = entity.get("transformation");
        if (value instanceof List<?> list && list.size() == 16) {
            float[] m = new float[16];
            for (int i = 0; i < 16; i++) {
                m[i] = ((Number) list.get(i)).floatValue();
            }
            // Ordre des 16 nombres : ligne par ligne
            Matrix4f matrix = new Matrix4f(
                m[0], m[4], m[8], m[12],
                m[1], m[5], m[9], m[13],
                m[2], m[6], m[10], m[14],
                m[3], m[7], m[11], m[15]);
            return Affine.of(matrix);
        }
        if (value instanceof Snbt.Compound c) {
            return new Affine(
                vector(c.get("translation"), new Vector3f()),
                rotation(c.get("left_rotation")),
                vector(c.get("scale"), new Vector3f(1, 1, 1)),
                rotation(c.get("right_rotation")));
        }
        return Affine.IDENTITY;
    }

    private static Vector3f vector(Object value, Vector3f fallback) {
        if (value instanceof List<?> list && list.size() == 3) {
            return new Vector3f(((Number) list.get(0)).floatValue(), ((Number) list.get(1)).floatValue(), ((Number) list.get(2)).floatValue());
        }
        return fallback;
    }

    private static Quaternionf rotation(Object value) {
        if (value instanceof List<?> list && list.size() == 4) {
            return new Quaternionf(((Number) list.get(0)).floatValue(), ((Number) list.get(1)).floatValue(),
                ((Number) list.get(2)).floatValue(), ((Number) list.get(3)).floatValue()).normalize();
        }
        if (value instanceof Snbt.Compound c && c.number("angle") != null) {
            Vector3f axis = vector(c.get("axis"), new Vector3f(0, 1, 0)).normalize();
            return new Quaternionf().fromAxisAngleRad(axis, c.number("angle").floatValue());
        }
        return new Quaternionf();
    }

    /**
     * Boîte englobante approximative : cube 0..1 pour un bloc, tête réelle pour une tête de joueur affichée sans
     * contexte (cas des exports block-display.com), cube centré de côté 1 pour un autre objet ; les textes ne
     * comptent pas.
     */
    public static Bounds bounds(List<RawPart> parts) {
        Vector3f min = new Vector3f(Float.POSITIVE_INFINITY);
        Vector3f max = new Vector3f(Float.NEGATIVE_INFINITY);
        boolean any = false;
        for (RawPart part : parts) {
            Vector3f low;
            Vector3f high;
            if (part.type().equals("block_display")) {
                low = new Vector3f(0, 0, 0);
                high = new Vector3f(1, 1, 1);
            } else if (part.type().equals("item_display")) {
                if (isBareHead(part.data())) {
                    low = new Vector3f(-0.25f, -0.5f, -0.25f);
                    high = new Vector3f(0.25f, 0, 0.25f);
                } else {
                    low = new Vector3f(-0.5f);
                    high = new Vector3f(0.5f);
                }
            } else {
                continue;
            }
            Matrix4f matrix = part.transform().toMatrix();
            for (int i = 0; i < 8; i++) {
                Vector3f corner = new Vector3f((i & 1) == 0 ? low.x : high.x, (i & 2) == 0 ? low.y : high.y, (i & 4) == 0 ? low.z : high.z);
                matrix.transformPosition(corner);
                min.min(corner);
                max.max(corner);
                any = true;
            }
        }
        if (!any) {
            return new Bounds(new Vector3f(), new Vector3f());
        }
        return new Bounds(min, max);
    }

    /** Tête de joueur rendue sans transformation d'objet (contexte none ou head) : de -0,5 à 0 en hauteur. */
    private static boolean isBareHead(Snbt.Compound entity) {
        Snbt.Compound item = entity.compound("item");
        String id = item == null ? null : item.string("id");
        if (id == null || !id.endsWith("player_head")) {
            return false;
        }
        String context = entity.string("item_display");
        return context == null || context.equals("none") || context.equals("head");
    }

    /** Recentre les pièces sur le milieu de la boîte englobante. */
    public static List<RawPart> centered(List<RawPart> parts, Bounds bounds) {
        Vector3fc shift = bounds.center().negate();
        List<RawPart> out = new ArrayList<>(parts.size());
        for (RawPart part : parts) {
            out.add(new RawPart(part.type(), part.transform().shifted(shift), part.data()));
        }
        return out;
    }

    private static String token(String text, int[] cursor) {
        skipSpaces(text, cursor);
        int start = cursor[0];
        while (cursor[0] < text.length() && !Character.isWhitespace(text.charAt(cursor[0])) && text.charAt(cursor[0]) != '{') {
            cursor[0]++;
        }
        return text.substring(start, cursor[0]);
    }

    private static void skipSpaces(String text, int[] cursor) {
        while (cursor[0] < text.length() && Character.isWhitespace(text.charAt(cursor[0]))) {
            cursor[0]++;
        }
    }
}

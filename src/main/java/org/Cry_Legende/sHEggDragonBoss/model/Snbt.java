package org.Cry_Legende.sHEggDragonBoss.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecteur SNBT (format des données des commandes /summon) : composés, listes, tableaux typés, chaînes entre
 * guillemets simples ou doubles, nombres suffixés. Chaque composé garde aussi le texte brut de ses valeurs, pour
 * repasser les composants d'un objet tels quels au jeu.
 */
public final class Snbt {

    /** Composé SNBT : valeurs lues, plus le texte d'origine de chacune. */
    public static final class Compound extends LinkedHashMap<String, Object> {
        private final Map<String, String> raw = new LinkedHashMap<>();

        public String raw(String key) {
            return raw.get(key);
        }

        public Compound compound(String key) {
            return get(key) instanceof Compound c ? c : null;
        }

        public List<?> list(String key) {
            return get(key) instanceof List<?> l ? l : null;
        }

        public String string(String key) {
            Object value = get(key);
            return value instanceof String s ? s : null;
        }

        public Number number(String key) {
            return get(key) instanceof Number n ? n : null;
        }

        /** Vrai pour 1b, true ou tout nombre non nul. */
        public boolean flag(String key) {
            Object value = get(key);
            if (value instanceof Boolean b) {
                return b;
            }
            return value instanceof Number n && n.doubleValue() != 0;
        }
    }

    private final String text;
    private int pos;

    private Snbt(String text, int start) {
        this.text = text;
        this.pos = start;
    }

    /** Lit une valeur SNBT commençant à l'indice donné ; l'indice de fin est disponible via {@link Result#end()}. */
    public static Result read(String text, int start) {
        Snbt reader = new Snbt(text, start);
        Object value = reader.value();
        return new Result(value, reader.pos);
    }

    public static Object parse(String text) {
        Snbt reader = new Snbt(text, 0);
        Object value = reader.value();
        reader.skipWhitespace();
        if (reader.pos < text.length()) {
            throw reader.error("texte en trop après la valeur");
        }
        return value;
    }

    public record Result(Object value, int end) {
    }

    private Object value() {
        skipWhitespace();
        if (pos >= text.length()) {
            throw error("valeur attendue");
        }
        char c = text.charAt(pos);
        if (c == '{') {
            return compound();
        }
        if (c == '[') {
            return list();
        }
        if (c == '"' || c == '\'') {
            return quoted();
        }
        return scalar(unquoted());
    }

    private Compound compound() {
        expect('{');
        Compound out = new Compound();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return out;
        }
        while (true) {
            skipWhitespace();
            String key = peek() == '"' || peek() == '\'' ? quoted() : unquoted();
            if (key.isEmpty()) {
                throw error("clé attendue");
            }
            skipWhitespace();
            expect(':');
            skipWhitespace();
            int start = pos;
            Object value = value();
            out.put(key, value);
            out.raw.put(key, text.substring(start, pos).trim());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw error("',' ou '}' attendu");
            }
        }
    }

    private Object list() {
        expect('[');
        skipWhitespace();
        if (pos + 1 < text.length() && text.charAt(pos + 1) == ';' && "BIL".indexOf(Character.toUpperCase(text.charAt(pos))) >= 0) {
            char type = Character.toUpperCase(text.charAt(pos));
            pos += 2;
            List<Number> values = new ArrayList<>();
            skipWhitespace();
            if (peek() != ']') {
                while (true) {
                    Object v = value();
                    if (!(v instanceof Number n)) {
                        throw error("nombre attendu dans un tableau typé");
                    }
                    values.add(n);
                    skipWhitespace();
                    char c = next();
                    if (c == ']') {
                        break;
                    }
                    if (c != ',') {
                        throw error("',' ou ']' attendu");
                    }
                }
            } else {
                pos++;
            }
            return switch (type) {
                case 'B' -> {
                    byte[] out = new byte[values.size()];
                    for (int i = 0; i < out.length; i++) {
                        out[i] = values.get(i).byteValue();
                    }
                    yield out;
                }
                case 'L' -> values.stream().mapToLong(Number::longValue).toArray();
                default -> values.stream().mapToInt(Number::intValue).toArray();
            };
        }
        List<Object> out = new ArrayList<>();
        if (peek() == ']') {
            pos++;
            return out;
        }
        while (true) {
            out.add(value());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw error("',' ou ']' attendu");
            }
        }
    }

    private String quoted() {
        char quote = next();
        StringBuilder out = new StringBuilder();
        while (pos < text.length()) {
            char c = text.charAt(pos++);
            if (c == '\\' && pos < text.length()) {
                char escaped = text.charAt(pos++);
                switch (escaped) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 's' -> out.append(' ');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> out.append(escaped);
                }
            } else if (c == quote) {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        throw error("chaîne non fermée");
    }

    private String unquoted() {
        int start = pos;
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '+') {
                pos++;
            } else {
                break;
            }
        }
        return text.substring(start, pos);
    }

    private static Object scalar(String token) {
        if (token.equalsIgnoreCase("true")) {
            return Boolean.TRUE;
        }
        if (token.equalsIgnoreCase("false")) {
            return Boolean.FALSE;
        }
        if (token.isEmpty()) {
            return token;
        }
        char suffix = Character.toLowerCase(token.charAt(token.length() - 1));
        String body = token.substring(0, token.length() - 1);
        try {
            switch (suffix) {
                case 'b':
                    return Byte.parseByte(body);
                case 's':
                    return Short.parseShort(body);
                case 'l':
                    return Long.parseLong(body);
                case 'f':
                    return Float.parseFloat(body);
                case 'd':
                    return Double.parseDouble(body);
                default:
                    break;
            }
        } catch (NumberFormatException ignored) {
            // pas un nombre suffixé : chaîne sans guillemets
        }
        try {
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            return Integer.parseInt(token);
        } catch (NumberFormatException e) {
            return token;
        }
    }

    private void skipWhitespace() {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= text.length()) {
            throw error("fin de texte inattendue");
        }
        return text.charAt(pos++);
    }

    private void expect(char c) {
        if (next() != c) {
            pos--;
            throw error("'" + c + "' attendu");
        }
    }

    private IllegalArgumentException error(String message) {
        int from = Math.max(0, pos - 20);
        int to = Math.min(text.length(), pos + 20);
        return new IllegalArgumentException(message + " (position " + pos + " : ..." + text.substring(from, to) + "...)");
    }
}

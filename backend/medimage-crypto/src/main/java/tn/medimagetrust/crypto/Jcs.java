package tn.medimagetrust.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Canonicalisation JSON selon RFC 8785 (JCS), à appliquer avant toute signature. */
public final class Jcs {

    private static final BigInteger MAX_SAFE = BigInteger.valueOf(9007199254740991L);

    private Jcs() {
    }

    /** Renvoie la forme canonique du JSON, encodée en UTF-8. */
    public static byte[] canonicalize(String json) {
        Object value = new Parser(json).parseDocument();
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof BigInteger n) {
            sb.append(n.toString());
        } else if (v instanceof String s) {
            writeString(s, sb);
        } else if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString((String) e.getKey(), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else {
            throw new IllegalStateException("Type inattendu : " + v.getClass());
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Parseur JSON minimal : objets (clés triées), tableaux, chaînes, entiers, booléens, null. */
    private static final class Parser {
        private final String s;
        private int i = 0;

        Parser(String s) {
            this.s = s;
        }

        Object parseDocument() {
            skipWs();
            Object v = parseValue();
            skipWs();
            if (i != s.length()) {
                fail("contenu après la fin du document");
            }
            return v;
        }

        private Object parseValue() {
            if (i >= s.length()) {
                fail("fin inattendue");
            }
            char c = s.charAt(i);
            if (c == '{') {
                return parseObject();
            } else if (c == '[') {
                return parseArray();
            } else if (c == '"') {
                return parseString();
            } else if (c == 't') {
                literal("true");
                return Boolean.TRUE;
            } else if (c == 'f') {
                literal("false");
                return Boolean.FALSE;
            } else if (c == 'n') {
                literal("null");
                return null;
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                return parseNumber();
            }
            return fail("caractère inattendu '" + c + "'");
        }

        private Map<String, Object> parseObject() {
            i++; // {
            Map<String, Object> map = new TreeMap<>(); // ordre naturel = unités UTF-16, comme JCS
            skipWs();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    fail("clé attendue");
                }
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                Object value = parseValue();
                if (map.containsKey(key)) {
                    fail("clé dupliquée : " + key);
                }
                map.put(key, value);
                skipWs();
                if (peek() == ',') {
                    i++;
                } else {
                    expect('}');
                    return map;
                }
            }
        }

        private List<Object> parseArray() {
            i++; // [
            List<Object> list = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                skipWs();
                list.add(parseValue());
                skipWs();
                if (peek() == ',') {
                    i++;
                } else {
                    expect(']');
                    return list;
                }
            }
        }

        private String parseString() {
            i++; // guillemet ouvrant
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    fail("chaîne non terminée");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        fail("échappement incomplet");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (i + 4 > s.length()) {
                                fail("\\u incomplet");
                            }
                            int cp = 0;
                            for (int k = 0; k < 4; k++) {
                                int d = Character.digit(s.charAt(i + k), 16);
                                if (d < 0) {
                                    fail("chiffre hexadécimal invalide");
                                }
                                cp = cp * 16 + d;
                            }
                            i += 4;
                            sb.append((char) cp);
                        }
                        default -> fail("échappement invalide \\" + e);
                    }
                } else if (c < 0x20) {
                    fail("caractère de contrôle non échappé");
                } else {
                    sb.append(c);
                }
            }
        }

        private BigInteger parseNumber() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            if (peek() == '0') {
                i++;
            } else if (peek() >= '1' && peek() <= '9') {
                while (peek() >= '0' && peek() <= '9') {
                    i++;
                }
            } else {
                fail("nombre invalide");
            }
            if (peek() == '.' || peek() == 'e' || peek() == 'E') {
                fail("nombre non entier non supporté");
            }
            BigInteger n = new BigInteger(s.substring(start, i));
            if (n.abs().compareTo(MAX_SAFE) > 0) {
                fail("entier hors de la plage sûre (2^53 - 1)");
            }
            return n;
        }

        private void literal(String word) {
            if (!s.startsWith(word, i)) {
                fail("littéral invalide");
            }
            i += word.length();
        }

        private void skipWs() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private void expect(char c) {
            if (peek() != c) {
                fail("'" + c + "' attendu");
            }
            i++;
        }

        private <T> T fail(String message) {
            throw new IllegalArgumentException("JSON invalide à la position " + i + " : " + message);
        }
    }
}

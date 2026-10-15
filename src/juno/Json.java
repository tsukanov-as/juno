package juno;

import java.math.BigDecimal;
import java.util.*;

/** Dependency-free JSON boundary. Numbers stay decimal, never pass through double. */
public final class Json {
    private Json() {}
    public static Object parse(String text) {
        Reader r = new Reader(text); Object value = r.value(0); r.space();
        if (r.at != text.length()) throw r.error("Trailing JSON data");
        return value;
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?>)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String,Object>) value;
    }
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        if (!(value instanceof List<?>)) throw new IllegalArgumentException("Expected JSON array");
        return (List<Object>) value;
    }
    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder(); write(value, out, 0); return out.toString();
    }
    private static void write(Object value, StringBuilder out, int depth) {
        if (depth > 100) throw new IllegalArgumentException("JSON nesting exceeds 100");
        if (value == null) out.append("null");
        else if (value instanceof String text) {
            out.append('"');
            for (char c : text.toCharArray()) switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32 || Character.isSurrogate(c)) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
            out.append('"');
        } else if (value instanceof BigDecimal || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long || value instanceof Boolean) out.append(value);
        else if (value instanceof Map<?,?> map) {
            out.append('{'); boolean first = true;
            for (var entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("JSON keys must be strings");
                if (!first) out.append(','); first = false;
                write(entry.getKey(), out, depth + 1); out.append(':'); write(entry.getValue(), out, depth + 1);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('['); boolean first = true;
            for (Object item : list) { if (!first) out.append(','); first = false; write(item, out, depth + 1); }
            out.append(']');
        } else throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }
    private static final class Reader {
        final String text; int at;
        Reader(String text) { this.text = text; }
        IllegalArgumentException error(String message) { return new IllegalArgumentException(message + " at JSON offset " + at); }
        void space() { while (at < text.length() && " \r\n\t".indexOf(text.charAt(at)) >= 0) at++; }
        boolean take(char c) { space(); if (at < text.length() && text.charAt(at) == c) { at++; return true; } return false; }
        Object value(int depth) {
            if (depth > 100) throw error("JSON nesting exceeds 100");
            space(); if (at == text.length()) throw error("Expected value");
            char c = text.charAt(at);
            if (c == '"') return string();
            if (take('{')) {
                Map<String,Object> map = new LinkedHashMap<>(); if (take('}')) return map;
                do {
                    space(); if (at == text.length() || text.charAt(at) != '"') throw error("Expected object key");
                    String key = string(); if (!take(':')) throw error("Expected ':'");
                    if (map.containsKey(key)) throw error("Duplicate key: " + key);
                    map.put(key, value(depth + 1));
                } while (take(','));
                if (!take('}')) throw error("Expected '}'"); return map;
            }
            if (take('[')) {
                List<Object> list = new ArrayList<>(); if (take(']')) return list;
                do { list.add(value(depth + 1)); } while (take(','));
                if (!take(']')) throw error("Expected ']'"); return list;
            }
            for (String literal : List.of("true", "false", "null")) if (text.startsWith(literal, at)) {
                at += literal.length(); return switch (literal) { case "true" -> true; case "false" -> false; default -> null; };
            }
            int start = at; if (at < text.length() && text.charAt(at) == '-') at++;
            if (at == text.length()) throw error("Expected number");
            if (text.charAt(at) == '0') at++;
            else { int n = at; while (at < text.length() && digit(text.charAt(at))) at++; if (n == at) throw error("Expected number"); }
            if (at < text.length() && text.charAt(at) == '.') { at++; digits(); }
            if (at < text.length() && "eE".indexOf(text.charAt(at)) >= 0) {
                at++; if (at < text.length() && "+-".indexOf(text.charAt(at)) >= 0) at++; digits();
            }
            try { return new BigDecimal(text.substring(start, at)); }
            catch (NumberFormatException e) { throw error("Invalid number"); }
        }
        boolean digit(char c) { return c >= '0' && c <= '9'; }
        void digits() { int n = at; while (at < text.length() && digit(text.charAt(at))) at++; if (at == n) throw error("Expected digit"); }
        String string() {
            at++; StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++); if (c == '"') return out.toString();
                if (c < 32) throw error("Unescaped control character");
                if (c != '\\') { out.append(c); continue; }
                if (at == text.length()) throw error("Incomplete escape");
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> out.append(escape);
                    case 'b' -> out.append('\b'); case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n'); case 'r' -> out.append('\r'); case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) throw error("Incomplete Unicode escape");
                        for(int i=at;i<at+4;i++)if("0123456789abcdefABCDEF".indexOf(text.charAt(i))<0)throw error("Invalid Unicode escape");
                        try { out.append((char)Integer.parseInt(text.substring(at, at + 4), 16)); }
                        catch (NumberFormatException e) { throw error("Invalid Unicode escape"); }
                        at += 4;
                    }
                    default -> throw error("Invalid escape");
                }
            }
            throw error("Unterminated string");
        }
    }
}

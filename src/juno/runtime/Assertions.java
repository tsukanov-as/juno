package juno.runtime;

import java.util.*;
import static juno.runtime.RuntimeSupport.*;
import static juno.runtime.IoSupport.*;

/** Small BSL assertion object: value diff and exported-method exception check. */
final class Assertions implements NativeObject, TypedValue {
    public String bslTypeName() { return "Утверждения"; }
    public String toString() { return bslTypeName(); }
    public Object property(String name) { return missingProperty(bslTypeName(), name); }
    public Object invoke(String name, Object[] raw) {
        Object[] args = values(raw);
        switch (name) {
            case "равно", "equal": {
                arity(name, args, 2, 3);
                String difference = diff(args[0], args[1], "$", new IdentityHashMap<>(), 0);
                if (difference != null) throw error(string(optional(args, 2, "")) + difference);
                return UNDEFINED;
            }
            case "истина", "true": {
                arity(name, args, 1, 2);
                if (!truth(args[0])) throw error(string(optional(args, 1, "")) + " expected true");
                return UNDEFINED;
            }
            case "ложь", "false": {
                arity(name, args, 1, 2);
                if (truth(args[0])) throw error(string(optional(args, 1, "")) + " expected false");
                return UNDEFINED;
            }
            case "содержит", "contains": {
                arity(name, args, 2, 2);
                if (!string(args[0]).contains(string(args[1])))
                    throw error("Expected text to contain " + show(args[1]) + ", got " + show(args[0]));
                return UNDEFINED;
            }
            case "провал", "fail": {
                arity(name, args, 1, 1);
                throw error(string(args[0]));
            }
            case "вызываетисключение", "throws": {
                arity(name, args, 2, 4);
                Object values = optional(args, 2, UNDEFINED);
                if (!omitted(values) && !(values instanceof BArray)) throw error("Throws arguments require Array");
                String expected = string(optional(args, 3, ""));
                try {
                    call(args[0], string(args[1]), omitted(values) ? new Object[0] : ((BArray) values).values.toArray());
                } catch (RuntimeException e) {
                    if (!RuntimeSupport.description(e).contains(expected))
                        throw error("Exception message does not contain " + show(expected) + ": " + RuntimeSupport.description(e));
                    return UNDEFINED;
                }
                throw error("Expected exception from " + string(args[1]));
            }
            default: return missingMethod(bslTypeName(), name);
        }
    }
    private static String diff(Object actual, Object expected, String path, IdentityHashMap<Object,Set<Object>> seen, int depth) {
        if (actual == expected) return null;
        if (depth > 100) return path + ": assertion graph exceeds depth100";
        if (actual instanceof java.math.BigDecimal a && expected instanceof java.math.BigDecimal b && a.compareTo(b) == 0) return null;
        Set<Object> pairs = seen.computeIfAbsent(actual, k -> Collections.newSetFromMap(new IdentityHashMap<>()));
        if (!pairs.add(expected)) return null;
        if (actual instanceof BArray a && expected instanceof BArray b) {
            if (a.values.size() != b.values.size()) return path + ": array size expected " + b.values.size() + ", got " + a.values.size();
            for (int i = 0; i < a.values.size(); i++) {
                String d = diff(a.values.get(i), b.values.get(i), path + "[" + i + "]", seen, depth + 1);
                if (d != null) return d;
            }
            return null;
        }
        if (actual instanceof Struct a && expected instanceof Struct b) {
            if (!a.values.keySet().equals(b.values.keySet())) return path + ": structure keys differ";
            for (String key : a.values.keySet()) {
                String d = diff(a.values.get(key).value, b.values.get(key).value, path + "." + key, seen, depth + 1);
                if (d != null) return d;
            }
            return null;
        }
        if (actual instanceof BMap a && expected instanceof BMap b) {
            if (!a.values.keySet().equals(b.values.keySet())) return path + ": map keys differ";
            for (Object key : a.values.keySet()) {
                String d = diff(a.values.get(key).value, b.values.get(key).value, path + "[" + show(key) + "]", seen, depth + 1);
                if (d != null) return d;
            }
            return null;
        }
        if (Objects.equals(actual, expected)) return null;
        String position = "";
        if (actual instanceof String a && expected instanceof String b) {
            int i = 0;
            while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) i++;
            position = " (first difference at UTF16 index " + i + ")";
        }
        return path + position + ": expected " + show(expected) + ", got " + show(actual);
    }
    private static String show(Object value) {
        String text = string(value).replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
        return "\"" + (text.length() > 256 ? text.substring(0, 256) + "…" : text) + "\"";
    }
}

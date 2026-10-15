package juno;

import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

/** Strict, versioned local manifest. No package resolution or implicit loader. */
final class ProjectManifest {
    static final Set<String> KEYS = Set.of("schemaVersion", "name", "namespace", "entry", "sources", "tests",
        "exclude", "resources", "dependencies", "defines", "requires", "plugins", "params");
    final Path root, file;
    final Map<String,Object> json;
    final boolean versioned;
    final String entry, namespace;
    final int minimumJdk;
    final List<String> sources, tests, exclude, defines;
    final List<Resource> resources;
    final List<Dependency> dependencies;
    record Resource(String path, String prefix) {}
    record Dependency(String path, String namespace) {}

    private ProjectManifest(Path root, Path file, Map<String,Object> json) {
        this.root = root;
        this.file = file;
        this.json = json;
        versioned = json.containsKey("schemaVersion");
        for (String key : json.keySet()) if (!KEYS.contains(key)) throw bad("unknown key: " + key);
        if (!versioned && json.keySet().stream().anyMatch(k -> !Set.of("plugins", "params").contains(k)))
            throw bad("project fields require schemaVersion: 1");
        if (versioned && !(json.get("schemaVersion") instanceof BigDecimal n && n.compareTo(BigDecimal.ONE) == 0))
            throw bad("schemaVersion must be 1");
        if (json.containsKey("name")) text(json.get("name"), "name");
        entry = optionalText("entry", "");
        namespace = optionalText("namespace", "");
        if (!namespace.isEmpty()) Juno.className(namespace);
        sources = strings("sources", versioned ? List.of("src") : List.of("."));
        tests = strings("tests", versioned ? List.of("tests") : List.of("."));
        exclude = strings("exclude", List.of());
        defines = strings("defines", List.of());
        List<String> paths = new ArrayList<>(sources);
        paths.addAll(tests);
        paths.addAll(exclude);
        for (String path : paths) {
            safePath(path);
            if (path.contains("*") || path.contains("?"))
                throw bad("paths/exclude are prefixes, not glob patterns: " + path);
        }
        if (!entry.isEmpty()) safePath(entry);
        for (String define : defines) if (!define.matches("[\\p{L}_][\\p{L}\\p{N}_]*"))
            throw bad("invalid define: " + define);
        resources = new ArrayList<>();
        for (Object value : array("resources")) {
            Map<String,Object> r = object(value, "resource");
            keys(r, Set.of("path", "prefix"));
            String path = text(r.get("path"), "resource.path");
            String prefix = r.containsKey("prefix") ? text(r.get("prefix"), "resource.prefix") : "";
            safePath(path);
            resourceName(prefix, true);
            resources.add(new Resource(path, prefix));
        }
        dependencies = new ArrayList<>();
        for (Object value : array("dependencies")) {
            Map<String,Object> d = object(value, "dependency");
            keys(d, Set.of("path", "namespace"));
            String path = text(d.get("path"), "dependency.path");
            String ns = text(d.get("namespace"), "dependency.namespace");
            if (path.isBlank() || path.indexOf('\0') >= 0 || path.contains("\\"))
                throw bad("dependency path requires portable '/' separators");
            Juno.className(ns);
            dependencies.add(new Dependency(path, ns));
        }
        if (json.containsKey("plugins")) strings("plugins", List.of());
        if (json.containsKey("params")) object(json.get("params"), "params");
        int jdk = 21;
        if (json.containsKey("requires")) {
            Map<String,Object> r = object(json.get("requires"), "requires");
            keys(r, Set.of("jdk"));
            if (r.containsKey("jdk")) {
                Object v = r.get("jdk");
                if (!(v instanceof BigDecimal n) || n.scale() > 0 || n.compareTo(BigDecimal.valueOf(21)) < 0
                    || n.compareTo(BigDecimal.valueOf(Runtime.version().feature())) > 0)
                    throw bad("requires.jdk must be an available integer >=21 (running " + Runtime.version().feature() + ")");
                jdk = ((BigDecimal) v).intValueExact();
            }
        }
        minimumJdk = jdk;
    }

    static ProjectManifest read(Path root) throws IOException {
        root = root.toAbsolutePath().normalize();
        Path file = root.resolve(App.PROJECT);
        Map<String,Object> json;
        try { json = Files.exists(file) ? Json.object(Json.parse(Files.readString(file))) : Map.of(); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(file + ": " + e.getMessage(), e); }
        return new ProjectManifest(root, Files.exists(file) ? file : null, json);
    }

    static void validate(Path file, Map<String,Object> json) {
        new ProjectManifest(file.toAbsolutePath().getParent(), file.toAbsolutePath(), json);
    }

    private IllegalArgumentException bad(String message) {
        return new IllegalArgumentException((file == null ? root : file) + ": " + message);
    }
    private String optionalText(String key, String fallback) {
        return json.containsKey(key) ? text(json.get(key), key) : fallback;
    }
    private String text(Object value, String field) {
        if (!(value instanceof String s)) throw bad(field + " must be a string");
        return s;
    }
    private List<String> strings(String key, List<String> fallback) {
        if (!json.containsKey(key)) return fallback;
        List<String> values = new ArrayList<>();
        for (Object value : array(key)) values.add(text(value, key));
        return List.copyOf(values);
    }
    private List<Object> array(String key) {
        if (!json.containsKey(key)) return List.of();
        try { return Json.array(json.get(key)); }
        catch (IllegalArgumentException e) { throw bad(key + " must be an array"); }
    }
    private Map<String,Object> object(Object value, String field) {
        try { return Json.object(value); }
        catch (IllegalArgumentException e) { throw bad(field + " must be an object"); }
    }
    private void keys(Map<String,Object> map, Set<String> keys) {
        for (String key : map.keySet()) if (!keys.contains(key)) throw bad("unknown nested key: " + key);
    }

    static String safePath(String name) {
        if (name.isEmpty() || name.contains("\\") || name.indexOf('\0') >= 0 || Path.of(name).isAbsolute()
            || name.matches("^[A-Za-z]:.*"))
            throw new IllegalArgumentException("Expected project-relative portable path: " + name);
        for (String part : name.split("/", -1)) if (part.equals("..") || part.isEmpty())
            throw new IllegalArgumentException("Path traversal/empty segment: " + name);
        return name;
    }

    static String resourceName(String name, boolean empty) {
        if (empty && name.isEmpty()) return name;
        if (name.contains(":")) throw new IllegalArgumentException("Invalid resource name: " + name);
        safePath(name);
        for (String part : name.split("/")) if (part.equals(".") || part.codePoints().anyMatch(c -> c < 32 || c == 127))
            throw new IllegalArgumentException("Invalid resource name: " + name);
        return name;
    }

}

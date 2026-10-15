package juno;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Resolves explicit source/resource graphs. All module names and archives are portable. */
final class Project {
    record Module(Path path, String name, String display, boolean test, List<String> defines) {}
    /** problem: the name is usable as a class name but taken; that is an error only if the program mentions the module. */
    private record Candidate(Path file, String name, String display, boolean entry, String problem) {}
    final ProjectManifest manifest;
    final TreeMap<String,Module> modules = new TreeMap<>();
    final TreeMap<String,Path> resources = new TreeMap<>();
    final Map<String,String> names = new HashMap<>(), resourceNames = new HashMap<>(), namespaces = new HashMap<>();
    final Set<Path> active = new LinkedHashSet<>();
    final Map<Path,String> visited = new HashMap<>();
    final Path target;
    final String entry;
    /** The program file: its class name is made up when the file name is not a valid one (convert-files.bsl). */
    private final Path entryFile;
    /** Running or testing one file without schemaVersion builds the modules that the file reaches, not its whole folder. */
    private final boolean reachableOnly;
    int minimumJdk = 21;

    Project(Path input, boolean testing, List<String> commandDefines) throws IOException {
        target = input.toAbsolutePath().normalize();
        if (Files.isRegularFile(target) && !App.isSource(target))
            throw new IllegalArgumentException("Expected .bsl file: " + input);
        manifest = ProjectManifest.read(App.root(target));
        Path main = Files.isDirectory(target) ? manifest.entry.isEmpty() ? null : manifest.root.resolve(manifest.entry) : target;
        entryFile = main == null ? null : main.normalize();
        reachableOnly = Files.isRegularFile(target) && !manifest.versioned;
        visit(manifest, manifest.namespace, testing, commandDefines);
        entry = testing || main == null ? "" : modules.values().stream()
            .filter(m -> m.path.equals(main.normalize()) && !m.test).map(Module::name).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Entry is outside application sources/excluded: " + main));
        if (!testing && entry.isEmpty()) throw new IllegalArgumentException("Manifest entry required for directory: " + input);
    }

    private void visit(ProjectManifest m, String namespace, boolean testing, List<String> commandDefines) throws IOException {
        Path identity = m.root.toRealPath();
        if (active.contains(identity)) throw new IllegalArgumentException("Dependency cycle: " + active + " -> " + identity);
        String prior = visited.get(identity);
        if (prior != null) {
            if (!prior.equals(namespace)) throw new IllegalArgumentException("Library used with different namespaces: " + identity);
            return;
        }
        if (!m.namespace.isEmpty() && !m.namespace.equals(namespace))
            throw new IllegalArgumentException("Library namespace mismatch: expected " + m.namespace + ", got " + namespace);
        if (!namespace.isEmpty()) {
            String existing = namespaces.putIfAbsent(namespace.toLowerCase(Locale.ROOT), identity.toString());
            if (existing != null && !existing.equals(identity.toString()))
                throw new IllegalArgumentException("Dependency namespace conflict: " + namespace);
        }
        minimumJdk = Math.max(minimumJdk, m.minimumJdk);
        active.add(identity);
        List<String> defines = new ArrayList<>(m.defines);
        if (commandDefines != null) defines.addAll(commandDefines);
        List<String> effective = !m.versioned && defines.isEmpty() && commandDefines == null
            ? null : List.copyOf(new LinkedHashSet<>(defines));
        collect(m, m.sources, namespace, testing && !m.versioned, effective);
        if (testing && m.versioned) collect(m, m.tests, "tests", true, effective);
        for (ProjectManifest.Resource spec : m.resources) resource(m, spec, namespace);
        for (ProjectManifest.Dependency dependency : m.dependencies) {
            Path path = m.root.resolve(dependency.path()).normalize();
            if (!Files.isDirectory(path)) throw new IllegalArgumentException("Local dependency directory missing: " + path);
            visit(ProjectManifest.read(path), dependency.namespace(), false, commandDefines);
        }
        active.remove(identity);
        visited.put(identity, namespace);
    }

    /** Dot-directories cannot be module names, so skipping them hides no decision from the manifest. */
    private static final Set<String> TOOL_DIRECTORIES = Set.of(".git", ".hg", ".svn", ".idea", ".vscode", ".juno");
    /** Name conventions of projects without schemaVersion; a versioned manifest lists its composition itself. */
    private static final Set<String> LEGACY_OUTPUT = Set.of("build", "dist", "node_modules");
    private static final Set<String> LEGACY_TESTS = Set.of("tests", "test", "тесты");

    private boolean excluded(ProjectManifest m, Path file, boolean tests) {
        Path relative = m.root.relativize(file);
        for (Path part : relative) if (TOOL_DIRECTORIES.contains(part.toString())) return true;
        if (!m.versioned) for (Path part : relative) if (LEGACY_OUTPUT.contains(part.toString().toLowerCase(Locale.ROOT))) return true;
        for (String prefix : m.exclude) {
            Path normalized = Path.of(prefix).normalize();
            if (normalized.toString().isEmpty() || relative.startsWith(normalized)) return true;
        }
        Object configured = m.json.get("plugins");
        if (configured instanceof List<?> list) for (Object item : list) {
            Path plugin = Path.of((String) item);
            if (!plugin.isAbsolute()) plugin = m.root.resolve(plugin);
            if (file.startsWith(plugin.normalize())) return true;
        }
        if (!m.versioned) for (Path part : relative) if (part.toString().equalsIgnoreCase("plugins")) return true;
        if (!tests) {
            if (m.versioned) {
                for (String prefix : m.tests)
                    if (Path.of(prefix).normalize().toString().isEmpty() || relative.startsWith(Path.of(prefix).normalize())) return true;
            } else {
                for (Path part : relative) if (LEGACY_TESTS.contains(part.toString().toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    private void collect(ProjectManifest m, List<String> roots, String namespace, boolean test, List<String> defines) throws IOException {
        boolean root = m == manifest;
        List<Candidate> found = new ArrayList<>();
        for (String spec : roots) {
            Path base = m.root.resolve(spec).normalize();
            if (!Files.exists(base)) {
                if (test && m.versioned) continue;
                throw new IllegalArgumentException("Source root missing: " + base);
            }
            if (Files.isSymbolicLink(base) || !base.toRealPath().startsWith(m.root.toRealPath()))
                throw new IllegalArgumentException("Source root symlink escapes project: " + base);
            List<Path> files = Files.isRegularFile(base) ? List.of(base) : Juno.files(base);
            for (Path file : files) {
                boolean entryFile = root && file.equals(this.entryFile);
                if (!App.isSource(file) || Files.isSymbolicLink(file) || excluded(m, file, test) && !(entryFile && reachableOnly)) continue;
                Path relative = (Files.isDirectory(base) ? base : base.getParent()).relativize(file);
                String local = relative.toString().replace(java.io.File.separatorChar, '/');
                local = local.substring(0, local.lastIndexOf('.')).replace('/', '.');
                String qualified = namespace.isEmpty() ? local : namespace + "." + local;
                String displayNamespace = test && m.versioned ? m.namespace : namespace;
                String display = (displayNamespace.isEmpty() ? "" : displayNamespace.replace('.', '/') + "/")
                    + m.root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
                String name = qualified, problem = nameProblem(qualified);
                if (problem != null) {
                    if (entryFile) { name = entryName(qualified); problem = null; }
                    else if (!(root && reachableOnly)) throw new IllegalArgumentException(problem + " (" + display + ")");
                    else if (!isClassName(qualified)) continue; // nobody can write this name, so the program cannot use the file
                }
                found.add(new Candidate(file, name, display, entryFile, problem));
            }
        }
        if (root && reachableOnly) found = reachable(found);
        for (Candidate candidate : found) {
            String previous = names.putIfAbsent(candidate.name().toLowerCase(Locale.ROOT), candidate.display());
            if (previous != null) throw new IllegalArgumentException("Duplicate module name (case-insensitive): " + previous + " and " + candidate.display());
            modules.put(candidate.name(), new Module(candidate.file(), candidate.name(), candidate.display(), test, defines));
        }
    }

    /** Null when the name can be a module name, otherwise why it cannot. */
    private static String nameProblem(String qualified) {
        try { moduleName(qualified); return null; }
        catch (IllegalArgumentException e) { return e.getMessage(); }
    }

    private static boolean isClassName(String qualified) {
        for (String part : qualified.split("\\.", -1))
            if (part.isEmpty() || !Character.isJavaIdentifierStart(part.codePointAt(0)) || !part.codePoints().allMatch(Character::isJavaIdentifierPart)) return false;
        return true;
    }

    private static String moduleName(String qualified) {
        String name = Juno.className(qualified);
        if (juno.runtime.RuntimeSupport.isBuiltinTypeName(name))
            throw new IllegalArgumentException("Module conflicts with native type: " + name);
        if (App.class.getClassLoader().getResource(name.replace('.', '/') + ".class") != null)
            throw new IllegalArgumentException("Module conflicts with juno class: " + name);
        return name;
    }

    /** A name for the program file when its own is not a class name: dashes and spaces become '_', a taken name gets a suffix. */
    private static String entryName(String qualified) {
        StringBuilder name = new StringBuilder();
        for (String part : qualified.split("\\.", -1)) {
            StringBuilder fixed = new StringBuilder();
            part.codePoints().forEach(c -> fixed.appendCodePoint(Character.isJavaIdentifierPart(c) ? c : '_'));
            if (fixed.isEmpty() || !Character.isJavaIdentifierStart(fixed.codePointAt(0))) fixed.insert(0, '_');
            if (!name.isEmpty()) name.append('.');
            name.append(fixed);
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            try { return moduleName(name.toString()); }
            catch (IllegalArgumentException taken) { name.append('_'); }
        }
        throw new IllegalArgumentException("Cannot name the program module: " + qualified);
    }

    /** The program file and, transitively, the files whose module name its text mentions. */
    private List<Candidate> reachable(List<Candidate> found) throws IOException {
        Candidate first = found.stream().filter(Candidate::entry).findFirst().orElse(null);
        if (first == null) return found;
        // Selecting an entry by path must still reject two spellings of its portable module name.
        List<Candidate> selected = new ArrayList<>(found.stream().filter(c -> c.name().equalsIgnoreCase(first.name())).toList());
        Map<Candidate,Pattern> mentions = new HashMap<>();
        for (int next = 0; next < selected.size(); next++) {
            String text = Juno.read(selected.get(next).file()).toLowerCase(Locale.ROOT);
            for (Candidate other : found) {
                if (selected.contains(other)) continue;
                Pattern pattern = mentions.computeIfAbsent(other, c -> Pattern.compile("(?<![\\p{L}\\p{N}_.])"
                    + Pattern.quote(c.name().toLowerCase(Locale.ROOT)) + "(?![\\p{L}\\p{N}_])"));
                if (!pattern.matcher(text).find()) continue;
                if (other.problem() != null) throw new IllegalArgumentException(other.problem() + " (" + other.display() + ")");
                selected.add(other);
            }
        }
        return selected;
    }

    private void resource(ProjectManifest m, ProjectManifest.Resource spec, String namespace) throws IOException {
        Path base = m.root.resolve(spec.path()).normalize();
        if (!Files.exists(base) || Files.isSymbolicLink(base) || !base.toRealPath().startsWith(m.root.toRealPath()))
            throw new IllegalArgumentException("Invalid resource path: " + base);
        boolean directory = Files.isDirectory(base);
        if (!directory && !Files.isRegularFile(base)) throw new IllegalArgumentException("Resource must be a regular file/directory: " + base);
        List<Path> files;
        if (directory) try (var walk = Files.walk(base)) { files = walk.filter(Files::isRegularFile).sorted().toList(); }
        else files = List.of(base);
        for (Path file : files) {
            if (Files.isSymbolicLink(file) || !file.toRealPath().startsWith(base.toRealPath()))
                throw new IllegalArgumentException("Resource symlink/traversal: " + file);
            if (directory) {
                boolean hidden = false;
                for (Path part : base.relativize(file)) if (part.toString().startsWith(".") || Set.of("build", "node_modules").contains(part.toString())) hidden = true;
                if (hidden || App.isSource(file)) continue;
            }
            String local = (directory ? base.relativize(file) : file.getFileName()).toString().replace(java.io.File.separatorChar, '/');
            String name = (namespace.isEmpty() ? "" : namespace.replace('.', '/') + "/")
                + (spec.prefix().isEmpty() ? "" : spec.prefix() + "/") + local;
            ProjectManifest.resourceName(name, false);
            String previous = resourceNames.putIfAbsent(name.toLowerCase(Locale.ROOT), name);
            if (previous != null) throw new IllegalArgumentException("Resource collision: " + previous + " and " + name);
            resources.put(name, file);
        }
    }

    /** Resolve existing symlinks, including a symlink parent of a new output. */
    static Path physical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) return absolute.toRealPath();
        Path parent = absolute.getParent();
        return parent == null ? absolute : physical(parent).resolve(absolute.getFileName()).normalize();
    }

    /** Existing hardlinks share an inode even when their real path strings differ. */
    static boolean sameFile(Path first, Path second) throws IOException {
        return physical(first).equals(physical(second))
            || Files.exists(first) && Files.exists(second) && Files.isSameFile(first, second);
    }

    static Path runtimePath() throws IOException {
        try { return Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception e) { throw new IOException("Cannot locate runtime", e); }
    }

    void protect(Path output) throws IOException {
        Path target = physical(output);
        if (App.isSource(output) || App.isSource(target))
            throw new IllegalArgumentException("Output/report cannot overwrite a source path: " + output);
        List<Path> inputs = new ArrayList<>();
        modules.values().forEach(m -> inputs.add(m.path()));
        inputs.addAll(resources.values());
        for (Path root : visited.keySet()) {
            Path config = root.resolve(App.PROJECT);
            if (Files.exists(config)) inputs.add(config);
        }
        Path self = runtimePath();
        if (Files.isRegularFile(self)) inputs.add(self);
        for (Path input : inputs) if (sameFile(output, input))
            throw new IllegalArgumentException("Output/report would overwrite project input: " + input);
    }

    boolean selected(Module module) {
        if (!Files.isDirectory(target)) return module.path.equals(target);
        return module.test && (module.path.startsWith(target) || target.equals(manifest.root));
    }
}

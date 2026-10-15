package juno;

import juno.runtime.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** Project graph -> compiled modules -> scoped application or deterministic distributable JAR. */
public final class App {
    static final String INDEX = "META-INF/juno-source.index", DEBUG = "META-INF/juno-debug.json",
        METADATA = "META-INF/juno-program.json", RESOURCES = "META-INF/juno-resources/",
        RESOURCE_INDEX = "META-INF/juno-resources.index";
    static final String PROJECT = "juno.json", PARSER = "Парсер";
    private App() {}

    static Path projectFile(Path path) {
        Path dir = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) dir = dir.getParent();
        for (; dir != null; dir = dir.getParent()) {
            Path file = dir.resolve(PROJECT);
            if (Files.isRegularFile(file)) return file;
            if (Files.exists(dir.resolve(".git"))) break;
        }
        return null;
    }
    static Path root(Path path) {
        Path project = projectFile(path);
        if (project != null) return project.getParent();
        Path dir = path.toAbsolutePath().normalize();
        return Files.isDirectory(dir) ? dir : dir.getParent();
    }
    static boolean isSource(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".bsl");
    }
    static Path entry(Path source) {
        Path path = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path) || !isSource(path)) throw new IllegalArgumentException("Expected .bsl file: " + source);
        return path;
    }

    private record Bundle(Project project, Map<String,byte[]> classes, Map<String,ApplicationProgram.Source> sources) {
        ApplicationProgram program() {
            return new ApplicationProgram(sources, new ApplicationProgram.Resources() {
                public List<String> names() { return List.copyOf(project.resources.keySet()); }
                public InputStream open(String name) throws IOException {
                    Path file = project.resources.get(name);
                    if (file == null || Files.isSymbolicLink(file) || !Files.isRegularFile(file))
                        throw new IOException("Resource missing/not a regular file: " + name);
                    return Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                }
            });
        }
    }
    private static Bundle compile(Project project) throws IOException {
        Juno compiler = new Juno();
        compiler.moduleNames(project.modules.keySet());
        Map<String,byte[]> classes = new TreeMap<>();
        Map<String,ApplicationProgram.Source> sources = new TreeMap<>();
        List<Juno.Diagnostic> errors = new ArrayList<>();
        Throwable cause = null;
        for (Project.Module module : project.modules.values()) {
            String text = Juno.read(module.path());
            sources.put(module.name(), new ApplicationProgram.Source(module.display(), text));
            try { classes.put(module.name(), compiler.compile(text, module.display(), module.name(), module.defines())); }
            catch (Juno.Failure e) {
                errors.addAll(e.diagnostics);
                if (cause == null) cause = e;
            }
        }
        if (!errors.isEmpty()) throw new Juno.Failure(errors, cause);
        return new Bundle(project, classes, sources);
    }
    static Map<String,byte[]> compile(Path root, List<String> defines) throws IOException {
        return compile(new Project(root, true, defines)).classes;
    }
    static int run(Path source, List<String> arguments, List<String> defines, Consumer<String> messages) throws IOException {
        Bundle bundle = compile(new Project(source, false, defines));
        return start(load(bundle.classes), bundle.project.entry, arguments, messages, bundle.program());
    }
    private static ApplicationContext programContext(ApplicationProgram program, OutputStream out, OutputStream err) {
        ApplicationContext current = Execution.currentContext();
        ApplicationContext context;
        if (out == null) context = current == null ? ApplicationContext.system() : current.snapshot();
        else context = current == null
            ? new ApplicationContext(Path.of(""), System.getenv(), System.in, out, err)
            : new ApplicationContext(current.directory(), current.environment(), current.input(), out, err);
        context.program(program);
        return context;
    }
    private static int start(Map<String,Class<?>> types, String entry, List<String> args,
                             Consumer<String> messages, ApplicationProgram program) {
        try (var context = programContext(program, null, null);
             var scope = Execution.withContext(context); var app = application(types, messages)) {
            app.setArguments(args);
            app.runSource(entry);
            return 0;
        } catch (RuntimeSupport.Exit exit) { return exit.code; }
        catch (RuntimeException | StackOverflowError e) { throw new Juno.Failure(List.of(runtimeDiagnostic(e, program)), e, bslStack(e, program)); }
    }
    private static List<String> bslStack(Throwable error, ApplicationProgram program) {
        List<String> stack = new ArrayList<>();
        for (StackTraceElement frame : error.getStackTrace()) if (RuntimeSupport.isBslSourceFile(frame.getFileName()) && frame.getLineNumber() > 0) {
            ApplicationProgram.Source source = program.source(frame.getClassName());
            stack.add("  " + (source == null ? frame.getFileName() : source.file()) + ":" + frame.getLineNumber() + " (" + frame.getMethodName() + ")");
        }
        return stack;
    }
    private static Juno.Diagnostic runtimeDiagnostic(Throwable error, ApplicationProgram program) {
        for (Throwable e = error; e != null; e = e.getCause()) for (StackTraceElement frame : e.getStackTrace()) {
            if (RuntimeSupport.isBslSourceFile(frame.getFileName()) && frame.getLineNumber() > 0) {
                ApplicationProgram.Source source = program.source(frame.getClassName());
                String file = source == null ? frame.getFileName() : source.file();
                return new Juno.Diagnostic(file, "runtime", "juno", e.getClass().getSimpleName(), Juno.message(error),
                    frame.getLineNumber(), 1, frame.getLineNumber(), 1, 0, 0);
            }
        }
        return Juno.problem("", "runtime", error);
    }

    static int test(Path path, List<String> defines, PrintStream out) throws IOException {
        return test(path, defines, out, out, "*", "text");
    }
    static int test(Path path, List<String> defines, PrintStream report, PrintStream body, String filter, String format) throws IOException {
        if (filter.isEmpty()) throw new IllegalArgumentException("Empty test filter");
        Project project = new Project(path, true, defines);
        Bundle bundle = compile(project);
        Map<String,Class<?>> types = load(bundle.classes);
        List<Map<String,Object>> records = new ArrayList<>();
        int total = 0, failed = 0;
        ApplicationProgram program = bundle.program();
        for (var module : types.entrySet()) {
            if (!project.selected(project.modules.get(module.getKey()))) continue;
            List<String> tests = Arrays.stream(module.getValue().getDeclaredMethods()).filter(m -> {
                String n = m.getName().toLowerCase(Locale.ROOT);
                return Modifier.isPublic(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())
                    && m.getReturnType() == void.class && m.getParameterCount() == 0
                    && (n.startsWith("тест") || n.startsWith("test"));
            }).map(Method::getName).sorted().toList();
            for (String test : tests) {
                String full = module.getKey() + "." + test;
                if (!matches(filter, full)) continue;
                total++;
                Map<String,Object> record = new LinkedHashMap<>();
                record.put("type", "test");
                record.put("schemaVersion", 1);
                record.put("name", full);
                try (var context = programContext(program, body, body);
                     var scope = Execution.withContext(context); var app = application(types, body::println)) {
                    app.run(() -> RuntimeSupport.call(app.newModule(module.getKey(), new Object[0]), test, new Object[0]));
                    record.put("status", "ok");
                    record.put("diagnostics", List.of());
                } catch (RuntimeException | Error e) {
                    failed++;
                    Juno.Diagnostic diagnostic = runtimeDiagnostic(e, program);
                    Juno.Diagnostic callSite = testDiagnostic(e, module.getKey(), program);
                    record.put("status", "failed");
                    record.put("diagnostics", List.of(diagnostic.json()));
                    record.put("causes", causes(e));
                    record.put("testLocation", callSite.json());
                    if (format.equals("text")) report.println("✗ " + full + ": " + callSite.file() + ":" + callSite.line() + ": " + Juno.message(e));
                }
                records.add(record);
            }
        }
        if (total == 0) throw new IllegalArgumentException("No test procedures match " + filter + " in " + path);
        if (format.equals("text")) report.println("Тестов: " + total + ", упало: " + failed);
        else {
            Map<String,Object> summary = new LinkedHashMap<>();
            summary.put("type", "summary");
            summary.put("schemaVersion", 1);
            summary.put("tests", total);
            summary.put("failed", failed);
            records.add(summary);
            if (format.equals("json")) report.println(Json.stringify(records));
            else records.forEach(r -> report.println(Json.stringify(r)));
        }
        return failed;
    }
    private static Juno.Diagnostic testDiagnostic(Throwable error, String module, ApplicationProgram program) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) for (StackTraceElement frame : cause.getStackTrace()) {
            if (frame.getClassName().equals(module) && frame.getLineNumber() > 0) {
                ApplicationProgram.Source source = program.source(module);
                String file = source == null ? frame.getFileName() : source.file();
                return new Juno.Diagnostic(file, "runtime", "juno", cause.getClass().getSimpleName(), Juno.message(error),
                    frame.getLineNumber(), 1, frame.getLineNumber(), 1, 0, 0);
            }
        }
        return runtimeDiagnostic(error, program);
    }
    static boolean matches(String pattern, String name) {
        StringBuilder regex = new StringBuilder("(?iu)");
        for (char c : pattern.toCharArray()) regex.append(c == '*' ? ".*" : c == '?' ? "." : java.util.regex.Pattern.quote(String.valueOf(c)));
        return name.matches(regex.toString());
    }
    static List<Map<String,Object>> causes(Throwable error) {
        List<Map<String,Object>> values = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable e = error; e != null && seen.add(e); e = e.getCause())
            values.add(Map.of("type", e.getClass().getSimpleName(), "message", Juno.message(e), "location", Juno.location(e)));
        return values;
    }

    static void build(Path source, Path target, List<String> defines) throws IOException { build(source, target, defines, false); }
    static void build(Path source, Path target, List<String> defines, boolean debugSources) throws IOException {
        Bundle bundle = compile(new Project(source, false, defines));
        Path output = target.toAbsolutePath().normalize();
        if (!output.toString().endsWith(".jar")) throw new IllegalArgumentException("Output must end in .jar");
        bundle.project.protect(output);
        Path self;
        try { self = Path.of(App.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception e) { throw new IOException("Cannot locate juno.jar", e); }
        if (!Files.isRegularFile(self)) throw new IOException("Build with java -jar juno.jar");
        if (Project.sameFile(output, self)) throw new IllegalArgumentException("Output would overwrite juno.jar");
        Files.createDirectories(output.getParent());
        // createTempFile would leave the artifact owner-only (0600); a new file gets the caller's umask.
        Path temporary = output.getParent().resolve(".juno-" + UUID.randomUUID() + ".jar");
        try (ZipFile zip = new ZipFile(self.toFile())) {
            TreeMap<String,Archives.Content> entries = new TreeMap<>();
            for (var e : Collections.list(zip.entries())) {
                String name = e.getName();
                if (e.isDirectory() || name.equals("META-INF/MANIFEST.MF") || name.equals(INDEX) || name.equals(DEBUG)
                    || name.equals(METADATA) || name.equals(RESOURCE_INDEX) || name.startsWith(RESOURCES)) continue;
                entries.put(name, () -> zip.getInputStream(e));
            }
            bundle.classes.forEach((name, bytes) -> {
                if (entries.putIfAbsent(name.replace('.', '/') + ".class", Archives.bytes(bytes)) != null)
                    throw new IllegalArgumentException("JAR class conflict: " + name);
            });
            entries.put(INDEX, Archives.bytes((bundle.project.entry + "\n" + String.join("\n", bundle.classes.keySet()) + "\n").getBytes(StandardCharsets.UTF_8)));
            entries.put(RESOURCE_INDEX, Archives.bytes(String.join("\n", bundle.project.resources.keySet()).getBytes(StandardCharsets.UTF_8)));
            bundle.project.resources.forEach((name, file) -> entries.put(RESOURCES + name, Archives.file(file)));
            Map<String,Object> paths = new TreeMap<>();
            bundle.sources.forEach((name, sourceInfo) -> paths.put(name, sourceInfo.file()));
            Map<String,Object> metadata = new TreeMap<>();
            metadata.put("schemaVersion", 1);
            metadata.put("minimumJdk", bundle.project.minimumJdk);
            metadata.put("sources", paths);
            entries.put(METADATA, Archives.bytes(Json.stringify(metadata).getBytes(StandardCharsets.UTF_8)));
            if (debugSources) {
                Map<String,Object> debug = new TreeMap<>();
                bundle.sources.forEach((name, s) -> debug.put(name, new TreeMap<>(Map.of("file", s.file(), "text", s.text()))));
                entries.put(DEBUG, Archives.bytes(Json.stringify(debug).getBytes(StandardCharsets.UTF_8)));
            }
            Archives.write(temporary, App.class.getName(), entries);
        } catch (Throwable e) {
            Files.deleteIfExists(temporary);
            throw e;
        }
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
    }

    public static void main(String[] args) {Juno.launch(() -> runArchive(args));}
    private static void runArchive(String[] args) {
        PrintStream errors=ConsoleIo.standardError();
        try (InputStream index = App.class.getResourceAsStream("/" + INDEX)) {
            Map<String,ApplicationProgram.Source> sources = new TreeMap<>();
            try (InputStream metadata = App.class.getResourceAsStream("/" + METADATA)) {
                if (metadata != null) {
                    Map<String,Object> data = Json.object(Json.parse(new String(metadata.readAllBytes(), StandardCharsets.UTF_8)));
                    int required = ((java.math.BigDecimal) data.get("minimumJdk")).intValueExact();
                    if (Runtime.version().feature() < required)
                        throw new IllegalArgumentException("Application requires JDK " + required + "+; running " + Runtime.version().feature());
                    Json.object(data.get("sources")).forEach((name, file) -> sources.put(name, new ApplicationProgram.Source((String) file, "")));
                }
            }
            if (index == null) throw new IllegalArgumentException("Not a juno program JAR");
            List<String> names = new String(index.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            Map<String,Class<?>> classes = new LinkedHashMap<>();
            for (String name : names.subList(1, names.size())) classes.put(name, Class.forName(name, false, App.class.getClassLoader()));
            try (InputStream debug = App.class.getResourceAsStream("/" + DEBUG)) {
                if (debug != null) Json.object(Json.parse(new String(debug.readAllBytes(), StandardCharsets.UTF_8))).forEach((name, data) -> {
                    Map<String,Object> s = Json.object(data);
                    sources.put(name, new ApplicationProgram.Source((String) s.get("file"), (String) s.get("text")));
                });
            }
            List<String> resources;
            try (InputStream in = App.class.getResourceAsStream("/" + RESOURCE_INDEX)) {
                resources = in == null ? List.of() : new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            }
            ApplicationProgram program = new ApplicationProgram(sources, new ApplicationProgram.Resources() {
                public List<String> names() { return resources; }
                public InputStream open(String name) throws IOException {
                    InputStream in = App.class.getResourceAsStream("/" + RESOURCES + name);
                    if (in == null) throw new FileNotFoundException(name);
                    return in;
                }
            });
            int code = start(classes, names.getFirst(), List.of(args), text -> Execution.context().console().writeLine(text), program);
            if (code != 0) System.exit(code);
        } catch (Exception | StackOverflowError e) {
            String location = e instanceof Juno.Failure f ? f.diagnostics.getFirst().file() + ":" + f.diagnostics.getFirst().line() : Juno.location(e);
            errors.println("juno: " + (location.isEmpty() ? "" : location + ": ") + Juno.message(e));
            if(e instanceof Juno.Failure f)f.bslStack.forEach(errors::println);
            System.exit(3);
        }
    }
    private static Map<String,Class<?>> load(Map<String,byte[]> bytes) {
        class Loader extends ClassLoader {
            Loader() { super(App.class.getClassLoader()); }
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] value = bytes.get(name);
                if (value == null) throw new ClassNotFoundException(name);
                return defineClass(name, value, 0, value.length);
            }
        }
        Loader loader = new Loader();
        Map<String,Class<?>> types = new LinkedHashMap<>();
        for (String name : bytes.keySet()) try { types.put(name, Class.forName(name, false, loader)); }
        catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
        return types;
    }
    private static BackgroundJobs application(Map<String,Class<?>> modules, Consumer<String> messages) {
        Map<String,Class<?>> all = new LinkedHashMap<>(modules);
        if (all.keySet().stream().noneMatch(PARSER::equalsIgnoreCase)) try {
            all.put(PARSER, Class.forName("Parser", false, App.class.getClassLoader()));
        } catch (ClassNotFoundException e) { throw new IllegalStateException("Missing Parser", e); }
        return new BackgroundJobs(all, Math.min(4, Runtime.getRuntime().availableProcessors()), 128, messages);
    }
}

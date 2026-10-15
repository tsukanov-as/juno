import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
import java.util.stream.*;
import javax.tools.ToolProvider;

/**
 * Builds juno with nothing but a JDK 21+ on any OS:
 *
 *   java Build.java          build/juno.jar
 *   java Build.java test     build and run all tests
 *   java Build.java seed     test, then refresh bootstrap/seed.jar
 *
 * The parser and the compiler are written in BSL, so they are compiled by a previous
 * generation of themselves. The first generation comes from bootstrap/seed.jar.
 */
public class Build {
    static final Path BUILD = Path.of("build");
    static final Path CLASSES = BUILD.resolve("classes");
    static final Path JAR = BUILD.resolve("juno.jar");
    static final Path SEED = Path.of("bootstrap", "seed.jar");
    static final List<String> COMPILER = List.of("src/Parser.bsl", "Parser", "src/Compiler.bsl", "Compiler");

    public static void main(String[] args) throws Exception {
        String task = args.length == 0 ? "build" : args[0];
        if (!Files.exists(Path.of("src", "Parser.bsl"))) fail("Run from the repository root: java Build.java");
        if (!Set.of("build", "test", "seed").contains(task)) fail("Usage: java Build.java [test|seed]");
        long started = System.nanoTime();
        Path compiler = build();
        if (!task.equals("build")) test(compiler);
        if (task.equals("seed")) seed(compiler);
        System.out.printf("Done in %.0f s: %s%n", (System.nanoTime() - started) / 1e9, JAR);
    }

    /** Java runtime, then compiler generations until the result reproduces itself. */
    static Path build() throws Exception {
        delete(BUILD);
        step("Java runtime");
        List<String> sources = files(Path.of("src"), ".java");
        javac(CLASSES, null, sources);
        step("Compiler, generation 1 (compiled by " + SEED + ")");
        Path generation = BUILD.resolve("generation1");
        bootstrap("compile", SEED, generation, COMPILER);
        if (!sameClasses(generation, SEED)) {
            // The sources changed since the seed: let the new compiler compile itself once more.
            step("Compiler, generation 2 (compiled by generation 1)");
            Path next = BUILD.resolve("generation2");
            bootstrap("compile", generation, next, COMPILER);
            generation = next;
        }
        step("Package " + JAR);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(JAR))) {
            JarEntry metadata = new JarEntry("META-INF/MANIFEST.MF");
            metadata.setTime(0);
            jar.putNextEntry(metadata);
            jar.write("Manifest-Version: 1.0\r\nMain-Class: juno.Main\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            for (String file : files(CLASSES, ".class")) add(jar, CLASSES.relativize(Path.of(file)).toString(), Path.of(file));
            add(jar, "Parser.class", generation.resolve("Parser.class"));
            add(jar, "Compiler.class", generation.resolve("Compiler.class"));
            add(jar, "META-INF/LICENSE", Path.of("LICENSE"));
        }
        distribution();
        return generation;
    }

    static void test(Path compiler) throws Exception {
        step("Fixed point: the compiler compiles itself to the same bytes");
        Path again = BUILD.resolve("fixed-point");
        bootstrap("compile", compiler, again, COMPILER);
        if (!sameClasses(again, compiler)) fail("The compiler is not a fixed point: " + again + " differs from " + compiler);
        step("Tests in BSL: juno test tests");
        java(JAR.toString(), "juno.Main", "test", "tests");
        step("Tests in Java: what BSL cannot do");
        Path tests = BUILD.resolve("tests");
        javac(tests, JAR.toString(), files(Path.of("tests"), ".java"));
        for (String file : files(Path.of("tests"), ".java")) {
            String name = Path.of(file).getFileName().toString().replace(".java", "");
            java(JAR + File.pathSeparator + tests, name);
        }
    }

    /** Universal offline bundle; source timestamps and absolute checkout paths are absent. */
    static void distribution() throws IOException {
        String bundle="juno";
        Path root = BUILD.resolve(bundle);
        Files.createDirectories(root.resolve("lib"));
        Files.copy(JAR, root.resolve("lib/juno.jar"));
        for (String file : files(Path.of("distribution"), "")) {
            Path relative = Path.of("distribution").relativize(Path.of(file));
            Path target = root.resolve(relative);
            Files.createDirectories(target.getParent());
            Files.copy(Path.of(file), target);
        }
        root.resolve("bin/juno").toFile().setExecutable(true, false);
        for (String file : List.of("LICENSE", "README.md"))
            Files.copy(Path.of(file), root.resolve(file));
        for (String directory : List.of("examples", "plugins")) {
            for (String file : files(Path.of(directory), "")) {
                Path target = root.resolve(file);
                Files.createDirectories(target.getParent());
                Files.copy(Path.of(file), target);
            }
        }
        Path parserReference = root.resolve("src/Parser.bsl");
        Files.createDirectories(parserReference.getParent());
        Files.copy(Path.of("src/Parser.bsl"), parserReference);
        for (String file : files(Path.of("docs"), "")) {
            Path target = root.resolve(file);
            Files.createDirectories(target.getParent());
            Files.copy(Path.of(file), target);
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(BUILD.resolve(bundle+".zip")))) {
            for (String file : files(root, "")) {
                String name = BUILD.relativize(Path.of(file)).toString().replace(File.separatorChar, '/');
                ZipEntry entry = new ZipEntry(name);
                entry.setTime(0);
                zip.putNextEntry(entry);
                Files.copy(Path.of(file), zip);
                zip.closeEntry();
            }
        }
    }

    static void seed(Path compiler) throws IOException {
        step("Update " + SEED);
        Files.createDirectories(SEED.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(SEED))) {
            add(jar, "Parser.class", compiler.resolve("Parser.class"));
            add(jar, "Compiler.class", compiler.resolve("Compiler.class"));
        }
    }

    // --- helpers

    static void bootstrap(String command, Path classes, Path output, List<String> modules) throws Exception {
        List<String> args = new ArrayList<>(List.of("juno.Bootstrap", command, classes.toString(), output.toString()));
        args.addAll(modules);
        java(CLASSES.toString(), args.toArray(String[]::new));
    }

    static void java(String classpath, String... args) throws Exception {
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        String exe = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<>(List.of(exe, "-cp", classpath));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).inheritIO().start();
        if (process.waitFor() != 0) fail("Failed: " + String.join(" ", command));
    }

    static void javac(Path output, String classpath, List<String> sources) {
        var javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) fail("A JDK is required (javac not found), not just a JRE");
        List<String> args = new ArrayList<>(List.of("--release", "21", "-encoding", "UTF-8", "-nowarn", "-d", output.toString()));
        if (classpath != null) args.addAll(List.of("-cp", classpath));
        args.addAll(sources);
        if (javac.run(null, null, null, args.toArray(String[]::new)) != 0) fail("javac failed");
    }

    static boolean sameClasses(Path generation, Path seed) throws IOException {
        if (!Files.exists(seed)) return false;
        for (String name : List.of("Parser.class", "Compiler.class")) {
            byte[] expected;
            if (Files.isDirectory(seed)) expected = Files.readAllBytes(seed.resolve(name));
            else try (JarFile jar = new JarFile(seed.toFile())) {
                JarEntry entry = jar.getJarEntry(name);
                if (entry == null) return false;
                try (InputStream in = jar.getInputStream(entry)) { expected = in.readAllBytes(); }
            }
            if (!Arrays.equals(expected, Files.readAllBytes(generation.resolve(name)))) return false;
        }
        return true;
    }

    static Manifest manifest() {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "juno.Main");
        return manifest;
    }

    static void add(JarOutputStream jar, String name, Path file) throws IOException {
        JarEntry entry = new JarEntry(name.replace(File.separatorChar, '/'));
        entry.setTime(0);
        jar.putNextEntry(entry);
        Files.copy(file, jar);
        jar.closeEntry();
    }

    static List<String> files(Path root, String extension) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(extension)).map(Path::toString).sorted().toList();
        }
    }

    static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }

    static void step(String text) { System.out.println("== " + text); }

    static void fail(String message) { System.err.println("Build failed: " + message); System.exit(1); }
}

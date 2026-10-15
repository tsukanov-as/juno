import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import juno.Main;

/** Project graph, clean reports, artifacts and the install-to-application path. */
public final class ProjectTests {
    record Result(int code, String out, String err) {}
    static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    static final Path RUNTIME = Path.of("build/juno.jar").toAbsolutePath();

    static void check(boolean value, Object detail) {
        if (!value) throw new AssertionError(detail);
    }
    static Result cli(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = Main.execute(args, new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
    static Result process(Path cwd, String... args) throws Exception {
        Path stdout = Files.createTempFile("juno-project-out-", ".txt");
        Path stderr = Files.createTempFile("juno-project-err-", ".txt");
        try {
            ProcessBuilder command = new ProcessBuilder(args).directory(cwd.toFile())
                .redirectOutput(stdout.toFile()).redirectError(stderr.toFile());
            command.environment().put("JAVA_HOME", System.getProperty("java.home"));
            Process p = command.start();
            return new Result(p.waitFor(), Files.readString(stdout), Files.readString(stderr));
        } finally {
            Files.deleteIfExists(stdout);
            Files.deleteIfExists(stderr);
        }
    }
    static Path write(Path root, String name, String text) throws IOException {
        Path path = root.resolve(name);
        Files.createDirectories(path.getParent());
        return Files.writeString(path, text);
    }
    static Object json(String text) throws Exception {
        var parse = Class.forName("juno.Json").getDeclaredMethod("parse", String.class);
        parse.setAccessible(true);
        return parse.invoke(null, text);
    }
    static void success(Result r) { check(r.code == 0, r); }
    static void delete(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
        }
    }
    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("juno-projects-");
        try {
            starter(tmp);
            graph(tmp);
            guards(tmp);
            hardlinkGuards(tmp);
            sources(tmp);
            composition(tmp);
            programFiles(tmp);
            distribution(tmp);
            System.out.println("ProjectTests: PASS");
        } finally { delete(tmp); }
    }
    static void starter(Path tmp) throws Exception {
        Path project = tmp.resolve("starter");
        success(cli("init", project.toString()));
        check(cli("init", project.toString()).code == 2, "init must not overwrite");
        Result r = cli("run", project.toString());
        check(r.code == 0 && r.out.contains("Привет, мир!") && r.out.contains("Ресурс"), r);
        success(cli("test", project.toString(), "--filter", "*Приветствие*"));
        check(cli("test", project.toString(), "--filter", "*missing*").code == 2, "unmatched filter");
        write(project, "tests/Output.bsl", """
            Процедура ТестВывод() Экспорт
                Сообщить("message-body");
                Консоль.ВывестиСтроку("console-body");
                А=Новый Утверждения;
                А.Равно(Новый Массив, Новый Массив);
                А.Равно(Новый Структура("Число",1), Новый Структура("Число",1.0));
                Каталог=Новый ВременныйКаталог;
                Сообщить(Каталог.Путь);
            КонецПроцедуры
            """);
        r = cli("test", project.toString(), "--filter", "*Output*", "--format", "json");
        check(r.code == 0 && json(r.out) instanceof List<?> && r.err.contains("message-body")
            && r.err.contains("console-body"), r);
        String path = r.err.lines().filter(s -> s.contains("juno-test-")).findFirst().orElseThrow();
        check(!Files.exists(Path.of(path)), "test context must remove temporary directory");
        write(project, "src/Thrower.bsl", "Процедура Ошибка() Экспорт\n ВызватьИсключение \"helper failure\";\nКонецПроцедуры");
        write(project, "tests/Failure.bsl", "Процедура ТестОшибка() Экспорт\n П=Новый Thrower;\n П.Ошибка();\nКонецПроцедуры");
        r = cli("test", project.toString(), "--filter", "*Failure*", "--format", "json");
        check(r.code == 1, r);
        Map<?,?> failure = (Map<?,?>) ((List<?>) json(r.out)).getFirst();
        Map<?,?> diagnostic = (Map<?,?>) ((List<?>) failure.get("diagnostics")).getFirst();
        Map<?,?> callSite = (Map<?,?>) failure.get("testLocation");
        check(diagnostic.get("file").equals("src/Thrower.bsl") && callSite.get("file").equals("tests/Failure.bsl"), r);
        r = cli("test", project.toString(), "--filter", "*Failure*");
        check(r.code == 1 && r.out.contains("tests/Failure.bsl:3:"), r);
        Files.delete(project.resolve("tests/Failure.bsl"));
        Path jar = tmp.resolve("starter.jar");
        success(cli("build", project.toString(), "--output", jar.toString(), "--format", "json"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        r = process(outside, JAVA, "-jar", jar.toString(), "читатель");
        check(r.code == 0 && r.out.contains("Привет, читатель!") && r.out.contains("Ресурс"), r);
        try (JarFile archive = new JarFile(jar.toFile())) {
            check(archive.stream().noneMatch(e -> e.getName().startsWith("tests/")), "tests leaked into app");
            check(archive.getEntry("META-INF/juno-debug.json") == null, "source text defaults off");
            check(archive.stream().allMatch(e -> e.getTime() == 0), "app timestamps");
        }
    }
    static void graph(Path tmp) throws Exception {
        Path lib = tmp.resolve("shared"), util = tmp.resolve("utils");
        write(util, "juno.json", "{\"schemaVersion\":1,\"namespace\":\"Utils\",\"sources\":[\"src\"]}");
        write(util, "src/Tool.bsl", "Функция ЧислоДва() Экспорт Возврат 2; КонецФункции");
        write(lib, "juno.json", "{\"schemaVersion\":1,\"namespace\":\"Shared\",\"sources\":[\"src\"],\"dependencies\":[{\"path\":\"../utils\",\"namespace\":\"Utils\"}],\"resources\":[{\"path\":\"data\"}]}");
        write(lib, "data/value.txt", "shared-resource");
        write(lib, "src/Helper.bsl", "Функция Ответ() Экспорт У=Новый Utils.Tool; Возврат У.ЧислоДва()+40; КонецФункции");
        for (String app : List.of("first", "second")) {
            Path root = tmp.resolve(app);
            write(root, "juno.json", "{\"schemaVersion\":1,\"entry\":\"src/Main.bsl\",\"sources\":[\"src\"],\"exclude\":[\"./src/ignored\"],\"dependencies\":[{\"path\":\"../shared\",\"namespace\":\"Shared\"},{\"path\":\"../utils\",\"namespace\":\"Utils\"}]}");
            write(root, "src/Main.bsl", "П=Новый Shared.Helper; Сообщить(П.Ответ()); Сообщить(GetResourceText(\"Shared/value.txt\"));");
            write(root, "src/ignored/bad.bsl", "invalid bsl ignored");
            Result r = cli("run", root.toString());
            check(r.code == 0 && r.out.contains("42") && r.out.contains("shared-resource"), r);
            success(cli("lint", root.resolve("src/Main.bsl").toString()));
        }
        // A source module can itself contain tests; no library entry is needed.
        Path tests = write(util, "src/Check.bsl", "Процедура TestLibrary() Экспорт А=Новый Assertions; А.Equal(2,2); КонецПроцедуры");
        success(cli("test", tests.toString()));
        write(util, "juno.json", "{\"schemaVersion\":1,\"namespace\":\"Utils\",\"sources\":[\"src\"],\"dependencies\":[{\"path\":\"../shared\",\"namespace\":\"Shared\"}]}");
        Result r = cli("run", tmp.resolve("first").toString());
        check(r.code == 2 && r.err.contains("cycle"), r);
    }
    static boolean hardlink(Path alias, Path input) throws IOException {
        try {
            Files.createLink(alias, input);
            return true;
        } catch (UnsupportedOperationException | AccessDeniedException e) {
            return false;
        } catch (FileSystemException e) {
            String reason = Objects.toString(e.getReason(), "").toLowerCase(Locale.ROOT);
            if (reason.contains("not supported") || reason.contains("not permitted")
                || reason.contains("privilege") || reason.contains("permission denied")
                || reason.contains("access is denied")) return false;
            throw e;
        }
    }

    static void hardlinkGuards(Path tmp) throws Exception {
        Path root = tmp.resolve("hardlink-guard");
        Path config = write(root, "juno.json", "{}");
        Path source = write(root, "Main.bsl", "Процедура ТестПустой() Экспорт КонецПроцедуры\nСообщить(42);");
        for (Path input : List.of(config, source, RUNTIME)) {
            byte[] before = Files.readAllBytes(input);
            // The runtime can live on a checkout bind mount while tmp uses another filesystem.
            Path aliasRoot = input.equals(RUNTIME) ? RUNTIME.getParent() : root;
            Path alias = aliasRoot.resolve(input.getFileName() + ".hardlink-report.txt");
            if (!hardlink(alias, input)) continue;
            try {
                Result r = cli("test", root.toString(), "--output", alias.toString());
                check(r.code == 2 && Arrays.equals(before, Files.readAllBytes(input)), r);
                r = cli("build", source.toString(), "--report", alias.toString(), "--output", root.resolve("app.jar").toString());
                check(r.code == 2 && Arrays.equals(before, Files.readAllBytes(input)), r);
                // The alias extension must not hide a source/config/runtime build destination.
                Path jarAlias = aliasRoot.resolve(input.getFileName() + ".hardlink-alias.jar");
                check(hardlink(jarAlias, input), "hardlinks became unavailable in the same filesystem");
                try {
                    r = cli("build", source.toString(), "--output", jarAlias.toString());
                    check(r.code == 2 && Arrays.equals(before, Files.readAllBytes(input)), r);
                } finally { Files.deleteIfExists(jarAlias); }
                r = cli("lint", source.toString(), "--output", alias.toString());
                check(r.code == 2 && Arrays.equals(before, Files.readAllBytes(input)), r);
            } finally { Files.deleteIfExists(alias); }
        }
        Path output = write(root, "existing.jar", "preserve existing JAR");
        Path report = root.resolve("same-jar.report.txt");
        if (hardlink(report, output)) {
            try {
                Result r = cli("build", source.toString(), "--output", output.toString(), "--report", report.toString());
                check(r.code == 2 && Files.readString(output).equals("preserve existing JAR"), r);
            } finally { Files.deleteIfExists(report); }
        }
        Path custom = write(root, "custom.json", "{\"plugins\":[],\"params\":{}}");
        Path customAlias = root.resolve("custom-report.txt");
        if (hardlink(customAlias, custom)) {
            try {
                Result r = cli("lint", source.toString(), "--config", custom.toString(), "--output", customAlias.toString());
                check(r.code == 2 && Files.readString(custom).equals("{\"plugins\":[],\"params\":{}}"), r);
            } finally { Files.deleteIfExists(customAlias); }
        }
    }

    static void guards(Path tmp) throws Exception {
        check(cli("run", "--define").code == 2, "missing run option value");
        check(cli("run", "--unknown").code == 2, "unknown run option");
        Path root = tmp.resolve("guard");
        Path entry = write(root, "Main.bsl", "Сообщить(42);");
        Path config = write(root, "juno.json", "{}");
        Path report = write(root, "existing.txt", "do not truncate");
        Result r = cli("test", root.toString(), "--format", "bogus", "--output", report.toString());
        check(r.code == 2 && Files.readString(report).equals("do not truncate"), r);
        r = cli("build", entry.toString(), "--format", "json", "--report", config.toString());
        check(r.code == 2 && Files.readString(config).equals("{}"), r);
        r = cli("build", entry.toString(), "--output", entry.toString());
        check(r.code == 2 && Files.readString(entry).contains("42"), r);
        Path alias = root.resolve("alias.jar");
        try {
            Files.createSymbolicLink(alias, entry.getFileName());
            r = cli("build", entry.toString(), "--output", alias.toString());
            check(r.code == 2 && Files.readString(entry).contains("42"), r);
        } catch (UnsupportedOperationException | FileSystemException e) {
            // Windows runners may not grant symlink creation to the user.
        }
        for (String name : List.of("File", "Файл", "Array")) {
            Path module = write(root, name + ".bsl", "");
            // a module that shadows a native type is an error as soon as the program mentions it ...
            Path user = write(root, "UsesNative.bsl", "Х = Новый " + name + ";\n");
            r = cli("run", user.toString());
            check(r.code == 2 && r.err.contains("native type") && r.err.contains(name + ".bsl"), r);
            Files.delete(user);
            // ... and does not matter to a program that does not
            r = cli("run", entry.toString());
            check(r.code == 0 && r.out.strip().equals("42"), r);
            Files.delete(module);
        }
        Path custom = write(root, "custom.json", "{\"plugins\":[],\"params\":{}}");
        Files.writeString(config, "bad unrelated manifest");
        success(cli("lint", entry.toString(), "--config", custom.toString()));
        Files.writeString(config, "{}");
        r = cli("lint", entry.toString(), "--config", custom.toString(), "--output", custom.toString());
        check(r.code == 2 && Files.readString(custom).contains("plugins"), r);
        for (String source : List.of("#Использовать shared\n", "#Use shared\n", "ПодключитьСценарий(\"p\",\"C\");", "AttachScript(\"p\",\"C\");")) {
            Files.writeString(entry, source);
            r = cli("build", entry.toString(), "--output", tmp.resolve("bad.jar").toString(), "--format", "json");
            check(r.code != 0 && json(r.out) instanceof Map<?,?> && r.out.contains("UNSUPPORTED_OS_LOADER"), r);
        }
        Files.writeString(entry, "Сообщить(42);");
        var failing = new PrintStream(new OutputStream() { public void write(int b) throws IOException { throw new IOException("broken report"); } });
        int code = Main.execute(new String[]{"build", entry.toString(), "--output", tmp.resolve("ok.jar").toString()}, failing, new PrintStream(new ByteArrayOutputStream()));
        check(code == 3, "broken report must fail");
    }
    /** A versioned manifest alone decides what is built; directory names only matter without schemaVersion. */
    static void composition(Path tmp) throws Exception {
        String module = """
            Функция Имя() Экспорт
                Возврат "%s";
            КонецФункции
            """;
        String main = """
            Сообщить(Новый Tests.Helper().Имя());
            Сообщить(Новый Plugins.Ext().Имя());
            Сообщить(Новый Build.Gen().Имя());
            """;
        Path explicit = tmp.resolve("explicit-composition");
        write(explicit, "juno.json", "{\"schemaVersion\":1,\"entry\":\"src/Main.bsl\",\"sources\":[\"src\"],\"tests\":[\"spec\"],\"exclude\":[\"src/Plugins/Skipped\"]}");
        write(explicit, "src/Main.bsl", main);
        write(explicit, "src/Tests/Helper.bsl", module.formatted("helper"));
        write(explicit, "src/Plugins/Ext.bsl", module.formatted("ext"));
        write(explicit, "src/Build/Gen.bsl", module.formatted("gen"));
        write(explicit, "src/Plugins/Skipped/Hidden.bsl", module.formatted("hidden"));
        write(explicit, "spec/Only.bsl", module.formatted("only"));
        Result r = cli("run", explicit.toString());
        check(r.code == 0 && r.out.lines().toList().equals(List.of("helper", "ext", "gen")), r);
        Path jar = tmp.resolve("explicit-composition.jar");
        success(process(tmp, JAVA, "-jar", RUNTIME.toString(), "build", explicit.toString(), "--output", jar.toString()));
        var view = Files.getFileAttributeView(tmp, java.nio.file.attribute.PosixFileAttributeView.class);
        if (view != null) { // a built JAR is shared with other people: the permissions of any new file, not owner-only
            Path control = Files.createFile(tmp.resolve("mode-control"));
            check(Files.getPosixFilePermissions(jar).equals(Files.getPosixFilePermissions(control)),
                Files.getPosixFilePermissions(jar) + " vs " + Files.getPosixFilePermissions(control));
        }
        try (JarFile archive = new JarFile(jar.toFile())) {
            check(archive.getEntry("Tests/Helper.class") != null && archive.getEntry("Plugins/Ext.class") != null
                && archive.getEntry("Build/Gen.class") != null, "explicit sources are packed whatever their names");
            check(archive.getEntry("Plugins/Skipped/Hidden.class") == null && archive.getEntry("Only.class") == null,
                "manifest exclude and tests stay out of the application");
        }
        write(explicit, "src/Main.bsl", "Сообщить(Новый Only().Имя());\n");
        check(cli("run", explicit.toString()).code != 0, "tests path is not application source");

        Path legacy = tmp.resolve("legacy-composition");
        write(legacy, "juno.json", "{}");
        write(legacy, "Main.bsl", "Сообщить(Новый Tests.Helper().Имя());\n");
        write(legacy, "Tests/Helper.bsl", module.formatted("helper"));
        r = cli("run", legacy.resolve("Main.bsl").toString());
        check(r.code != 0 && (r.err + r.out).contains("Tests.Helper"), "without schemaVersion the Tests directory keeps being skipped: " + r);
    }
    /** A script is a program, not a class: odd file names run, and unrelated files of the folder stay out of it. */
    static void programFiles(Path tmp) throws Exception {
        Path scripts = tmp.resolve("odd-names");
        for (String name : List.of("convert-files", "my script", "2024_report", "Файл", "Соответствие", "Консоль", "Parser", "Compiler")) {
            Path script = write(scripts, name + ".bsl", "Сообщить(\"ok " + name + "\");\n");
            Result r = cli("run", script.toString());
            check(r.code == 0 && r.out.strip().equals("ok " + name), name + ": " + r);
        }
        Path script = scripts.resolve("my script.bsl"), jar = tmp.resolve("odd-name.jar");
        success(process(tmp, JAVA, "-jar", RUNTIME.toString(), "build", script.toString(), "--output", jar.toString()));
        Result packaged = process(tmp, JAVA, "-jar", jar.toString());
        check(packaged.code == 0 && packaged.out.strip().equals("ok my script"), packaged);

        Path root = tmp.resolve("shared-folder");
        write(root, "juno.json", "{\"plugins\":[]}");
        write(root, "Помощник.bsl", "Функция Привет() Экспорт Возврат \"привет\"; КонецФункции\n");
        write(root, "Подпапка/Вложенный.bsl", "Функция Привет() Экспорт Возврат \"вложенный:\" + Новый Помощник().Привет(); КонецФункции\n");
        write(root, "legacy/old/broken.bsl", "Процедура Сломано(\n");
        write(root, "1lib/x.bsl", "Сообщить(1);\n");
        write(root, "unrelated.bsl", "Это не код\n");
        write(root, "tools/build/gen.bsl", "Сообщить(\"из каталога build\");\n");
        Path tool = write(root, "tools/tool.bsl", "Сообщить(Новый Помощник().Привет());\n");
        Result r = cli("run", tool.toString());
        check(r.code == 0 && r.out.strip().equals("привет"), "unrelated files of the folder must not matter: " + r);
        r = cli("run", write(root, "tools/deep.bsl", "Сообщить(Новый Подпапка.Вложенный().Привет());\n").toString());
        check(r.code == 0 && r.out.strip().equals("вложенный:привет"), "modules reached through other modules: " + r);
        r = cli("run", root.resolve("tools/build/gen.bsl").toString());
        check(r.code == 0 && r.out.strip().equals("из каталога build"), "a file named on the command line is never excluded: " + r);
        r = cli("run", write(root, "tools/uses-broken.bsl", "Новый unrelated;\n").toString());
        check(r.code != 0 && r.err.contains("unrelated.bsl"), "a module the program uses is compiled and reported: " + r);
        r = cli("test", root.toString());
        check(r.code != 0 && r.err.contains("Invalid class name: 1lib.x (1lib/x.bsl)"), "a whole folder names the file with a bad module name: " + r);
    }
    static void sources(Path tmp) throws Exception {
        Path root = tmp.resolve("source-info");
        write(root, "juno.json", "{\"schemaVersion\":1,\"entry\":\"src/Main.bsl\",\"sources\":[\"src\"]}");
        write(root, "src/Main.bsl", """
            Попытка
                ВызватьИсключение "marker-secret";
            Исключение
                Инфо=ИнформацияОбОшибке();
                Сообщить(Инфо.ИмяМодуля);
                Сообщить(Инфо.НомерСтроки);
                Сообщить(Инфо.ИсходнаяСтрока);
            КонецПопытки;
            """);
        Result r = cli("run", root.toString());
        check(r.code == 0 && r.out.contains("src/Main.bsl") && r.out.contains("marker-secret"), r);
        for (boolean debug : List.of(false, true)) {
            Path jar = tmp.resolve(debug ? "debug.jar" : "plain.jar");
            List<String> args = new ArrayList<>(List.of(JAVA, "-jar", RUNTIME.toString(), "build", root.toString(), "--output", jar.toString()));
            if (debug) args.add("--debug-sources");
            success(process(tmp, args.toArray(String[]::new)));
            byte[] first = Files.readAllBytes(jar);
            success(process(tmp, args.toArray(String[]::new))); // fresh JVM hash-map seeds
            check(Arrays.equals(first, Files.readAllBytes(jar)), "JAR must reproduce across JVMs");
            r = process(tmp, JAVA, "-jar", jar.toString());
            check(r.code == 0 && r.out.contains("src/Main.bsl") && r.out.contains("marker-secret") == debug, r);
        }
    }
    static void distribution(Path tmp) throws Exception {
        success(cli("run", "examples/проект/app-a"));
        success(cli("run", "examples/проект/app-b"));
        success(cli("test", "examples/проект/app-a"));
        success(cli("api", "--format", "json"));
        try (JarFile archive = new JarFile(RUNTIME.toFile())) {
            check(archive.stream().allMatch(e -> e.getTime() == 0), "runtime timestamps");
        }
        Path extracted = Files.createDirectories(tmp.resolve("distribution"));
        try (JarFile zip = new JarFile("build/juno.zip")) {
            for (var entry : Collections.list(zip.entries())) {
                Path target = extracted.resolve(entry.getName());
                Files.createDirectories(target.getParent());
                try (InputStream input = zip.getInputStream(entry)) { Files.copy(input, target); }
            }
        }
        Path distribution = extracted.resolve("juno");
        Path bin = distribution.resolve("bin");
        Path sample = distribution.resolve("examples/проект/app-a");
        Result sampleResult = process(tmp, JAVA, "-jar", distribution.resolve("lib/juno.jar").toString(), "run", sample.toString());
        check(sampleResult.code == 0 && sampleResult.out.contains("Привет, А!"), sampleResult);
        Path initialized = tmp.resolve("installed-starter");
        success(process(tmp, JAVA, "-jar", distribution.resolve("lib/juno.jar").toString(), "init", initialized.toString()));
        success(process(tmp, JAVA, "-jar", distribution.resolve("lib/juno.jar").toString(), "test", initialized.toString()));
        Path application = tmp.resolve("installed-app.jar");
        success(process(tmp, JAVA, "-jar", distribution.resolve("lib/juno.jar").toString(), "build", initialized.toString(), "--output", application.toString()));
        delete(initialized);
        Result installedRun = process(tmp, JAVA, "-jar", application.toString());
        check(installedRun.code == 0 && installedRun.out.contains("Ресурс"), installedRun);
        boolean windows = System.getProperty("os.name").startsWith("Windows");
        Result r = windows
            ? process(tmp, "cmd.exe", "/c", bin.resolve("juno.cmd").toString(), "version")
            : process(tmp, "sh", bin.resolve("juno").toString(), "version");
        check(r.code == 0 && r.out.strip().equals("juno dev"), r);
        Path file = write(tmp, "argv/Main.bsl", """
            Для Каждого А Из АргументыКоманднойСтроки Цикл Сообщить("["+А+"]"); КонецЦикла;
            ЗавершитьРаботу(7);
            """);
        if (windows) {
            // A batch fixture keeps cmd's exact quoted source, including the empty argument.
            Path cmd = write(tmp, "argv-check.cmd", "@echo off\r\nchcp 65001 >nul\r\ncall \"" + bin.resolve("juno.cmd")
                + "\" run \"" + file + "\" \"\" \"two words\" \"a\\\"b\" \"Ё\"\r\nexit /b %ERRORLEVEL%\r\n");
            r = process(tmp, "cmd.exe", "/c", cmd.toString());
            check(r.code == 7 && r.out.lines().toList().equals(List.of("[]", "[two words]", "[a\"b]", "[Ё]")), r);
            Path ps = write(tmp, "argv-check.ps1", "\uFEFF& '" + bin.resolve("juno.ps1") + "' run '" + file
                + "' '' 'two words' 'a\"b' 'Ё'\nexit $LASTEXITCODE\n");
            r = process(tmp, "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps.toString());
        } else {
            r = process(tmp, "sh", bin.resolve("juno").toString(), "run", file.toString(), "", "two words", "a\"b", "Ё");
        }
        check(r.code == 7 && r.out.lines().toList().equals(List.of("[]", "[two words]", "[a\"b]", "[Ё]")), r);
    }
}

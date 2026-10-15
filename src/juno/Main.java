package juno;

import juno.runtime.Execution;
import juno.runtime.RuntimeSupport;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Command line: juno run | build | test | lint. */
public final class Main {
    private static final String HELP = """
        juno — компилятор 1С (BSL) для JVM

          juno run   <программа.bsl> [аргументы]  запустить программу;
                                              аргументы — в АргументыКоманднойСтроки
          juno build <программа.bsl>       собрать программу в jar: java -jar программа.jar
          juno test  <папка|модуль.bsl>    запустить тесты — экспортные процедуры Тест...()
          juno lint  <файл|папка>          проверить код: синтаксис и правила из плагинов
          juno init  [папка]               создать проект schemaVersion1
          juno api [--format json]         зарегистрированные globals и docs/api.md
          juno version

        Модули программы — все .bsl рядом с ней и в подпапках, каждый модуль — класс:
        Корзина.bsl → Новый Корзина, Справочники/Товар.bsl → Новый Справочники.Товар.

        Опции lint:
          --plugin   плагин .bsl или папка с плагинами; можно повторять.
                     Примеры плагинов лежат в папке plugins репозитория juno
          --params   файл JSON с параметрами плагинов: {"Плагин.bsl": {"Параметр": значение}}
          --config   файл настроек вместо juno.json. По умолчанию juno.json ищется
                     в проверяемой папке и выше, до корня git-репозитория:
                     {"plugins": ["проверки"], "params": {...}}
                     если указаны --plugin или --params, juno.json не читается
          --format   формат отчёта: text — для человека (по умолчанию);
                     json — для других программ: массив, по объекту на каждый файл и итоговый объект;
                     jsonl — те же объекты в формате JSON Lines, по одному на строку
          --output   записать отчёт в файл, а не на экран
          --jobs     сколько файлов проверять параллельно, по умолчанию 1
          --dialect  juno (по умолчанию) или 1c — только синтаксис платформы, без Новый Пакет.Класс
        Опции build:
          --output   имя jar, по умолчанию как у программы
          --debug-sources включить source snapshots в JAR (по умолчанию исходников нет)
          --format text|json|jsonl; --report файл диагностики
        Опции test:
          --filter шаблон module.Test с * и ?; --format text|json|jsonl; --output отчёт
        Опции run, build и test (у run — до имени программы):
          --define   известные символы Parser для #Если, например Сервер,НаСервере

        Код выхода: 0 — всё хорошо, 1 — есть замечания или упавшие тесты, 2 — неверный вызов, 3 — сбой.
        """;

    public static void main(String[] args) {
        Juno.launch(() -> {int code = execute(args, juno.runtime.ConsoleIo.standardOutput(), juno.runtime.ConsoleIo.standardError()); if(code!=0)System.exit(code);});
    }

    public static int execute(String[] args, PrintStream out, PrintStream err) {
        return execute(args, System.in, out, err);
    }
    /** Host-owned streams are borrowed for this invocation and are never closed. */
    public static int execute(String[] args, InputStream input, PrintStream out, PrintStream err) {
        boolean debug = Arrays.asList(args).contains("--debug");
        PrintStream report = out;
        boolean closeReport = false;
        try (var context = new juno.runtime.ApplicationContext(Path.of(""), System.getenv(), input, out, err);
             var io = Execution.withContext(context); var messages = Execution.messages(err::println)) {
            if (args.length == 0 || Set.of("help", "--help", "-h").contains(args[0])) { out.print(HELP); return 0; }
            if (Set.of("version", "--version").contains(args[0])) { out.println("juno " + Juno.VERSION); return 0; }
            if (args[0].equals("run")) return run(Arrays.copyOfRange(args, 1, args.length), out);
            Options options = new Options(Arrays.copyOfRange(args, 1, args.length));
            if (Set.of("build", "test").contains(args[0])) {
                options.check(args[0].equals("build")
                    ? Set.of("output", "report", "format", "define", "debug", "debug-sources")
                    : Set.of("output", "format", "define", "debug", "filter"));
                if (options.pos.isEmpty()) options.pos.add(".");
                options.positionals(1);
                format(options);
                defines(options.many("define"));
                if (options.has("filter") && options.one("filter", "*").isEmpty())
                    throw new IllegalArgumentException("Empty test filter");
                String reportName = args[0].equals("test") ? "output" : "report";
                if (options.has(reportName)) {
                    Path inputPath = Path.of(options.pos.getFirst());
                    Path target = Path.of(options.one(reportName, "")).toAbsolutePath().normalize();
                    new Project(inputPath, args[0].equals("test"), defines(options.many("define"))).protect(target);
                    if (App.isSource(target)) throw new IllegalArgumentException("Report cannot overwrite a source path");
                    if (args[0].equals("build") && Project.sameFile(target, buildTarget(options, inputPath)))
                        throw new IllegalArgumentException("Report and JAR output must differ");
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    report = new PrintStream(Files.newOutputStream(target), false, StandardCharsets.UTF_8);
                    closeReport = true;
                }
            }
            int result;
            boolean routed = Set.of("build", "test").contains(args[0]) && !format(options).equals("text");
            try (var bodyContext = new juno.runtime.ApplicationContext(context.directory(), context.environment(), input,
                      routed ? err : out, err);
                 var bodyScope = Execution.withContext(bodyContext)) {
                result = switch (args[0]) {
                case "init" -> init(options, out);
                case "api" -> api(options, out);
                case "build" -> build(options, report);
                case "lint" -> lint(options, out, err);
                case "test" -> test(options, report, err);
                default -> throw new IllegalArgumentException("Unknown command: " + args[0] + ". See juno help");
                };
            }
            report.flush();
            if (report.checkError()) {
                err.println("juno: Cannot write the report");
                return 3;
            }
            return result;
        } catch (Juno.Failure e) {
            if (machine(args)) report.println(Json.stringify(Map.of("type", "error", "schemaVersion",1,"diagnostics", e.diagnostics.stream().map(Juno.Diagnostic::json).toList(),"causes",App.causes(e))));
            else { e.diagnostics.forEach(d -> err.println(render(d))); e.bslStack.forEach(err::println); }
            if (debug) e.printStackTrace(err);
            return e.diagnostics.stream().anyMatch(d -> d.phase().equals("parse")) ? 1 : 3;
        } catch (IllegalArgumentException e) {
            error(args, report, err, 2, e); if (debug) e.printStackTrace(err); return 2;
        } catch (Exception | StackOverflowError e) {
            error(args, report, err, 3, e); if (debug) e.printStackTrace(err); return 3;
        } finally { if (closeReport) report.close(); }
    }

    /** juno run [options] program.bsl [arguments]: everything after the program goes to it as is. */
    private static int run(String[] args, PrintStream out) throws IOException {
        int program = 0;
        while (program < args.length && args[program].startsWith("--")) {
            if (FLAGS.contains(args[program].substring(2))) program++;
            else {
                if (program + 1 >= args.length || args[program + 1].startsWith("--"))
                    throw new IllegalArgumentException("Missing value for " + args[program]);
                program += 2;
            }
        }
        if (program == args.length) {
            String[] expanded = Arrays.copyOf(args, args.length + 1);
            expanded[args.length] = ".";
            args = expanded;
        }
        Options o = new Options(Arrays.copyOfRange(args, 0, program + 1));
        o.check(Set.of("define", "debug"));
        List<String> arguments = List.of(Arrays.copyOfRange(args, program + 1, args.length));
        return App.run(Path.of(o.pos.get(0)), arguments, defines(o.many("define")), text -> Execution.context().console().writeLine(text));
    }

    private static int test(Options o, PrintStream out, PrintStream err) throws IOException {
        o.check(Set.of("define", "debug", "filter", "format", "output"));
        if (o.pos.isEmpty()) o.pos.add(".");
        o.positionals(1);
        String format = format(o);
        return App.test(Path.of(o.pos.getFirst()), defines(o.many("define")), out,
            format.equals("text") ? out : err, o.one("filter", "*"), format) == 0 ? 0 : 1;
    }
    private static Path buildTarget(Options o, Path input) {
        String fallback = Files.isDirectory(input) ? input.toAbsolutePath().normalize().getFileName() + ".jar"
            : input.getFileName().toString().replaceFirst("(?i)\\.(bsl|os)$", "") + ".jar";
        return Path.of(o.one("output", fallback));
    }
    private static int build(Options o, PrintStream out) throws IOException {
        o.check(Set.of("output", "report", "format", "define", "debug", "debug-sources"));
        if (o.pos.isEmpty()) o.pos.add(".");
        o.positionals(1);
        String format = format(o);
        Path source = Path.of(o.pos.getFirst()), target = buildTarget(o, source);
        App.build(source, target, defines(o.many("define")), o.has("debug-sources"));
        if (format.equals("text")) out.println("Готово: " + target + "  запуск: java -jar " + target);
        else out.println(Json.stringify(Map.of("type", "build", "schemaVersion", 1,
            "status", "ok", "output", target.toString(), "diagnostics", List.of())));
        return 0;
    }
    private static String format(Options o) {
        String format = o.one("format", "text");
        if (!Set.of("text", "json", "jsonl").contains(format)) throw new IllegalArgumentException("--format must be text,json,jsonl");
        return format;
    }
    private static int init(Options o, PrintStream out) throws IOException {
        o.check(Set.of());
        if (o.pos.size() > 1) throw new IllegalArgumentException("init expects zero or one directory");
        Path path = Path.of(o.pos.isEmpty() ? "." : o.pos.getFirst());
        ProjectInit.create(path);
        out.println("Создан проект: " + path);
        return 0;
    }
    private static int api(Options o, PrintStream out) {
        o.check(Set.of("format"));
        o.positionals(0);
        List<Map<String,Object>> signatures = juno.runtime.GlobalFunctions.signatures().stream()
            .map(s -> Map.<String,Object>of("ru", s.russianName(), "en", s.englishName(),
                "minArguments", s.minArguments(), "maxArguments", s.maxArguments())).toList();
        if (format(o).equals("text")) {
            out.println("Registered globals; constructors/defaults/limits: docs/api.md");
            for (var signature : signatures) out.println(signature.get("ru") + " / " + signature.get("en")
                + " (" + signature.get("minArguments") + ".." + signature.get("maxArguments") + ")");
        } else out.println(Json.stringify(Map.of("schemaVersion", 1, "version", Juno.VERSION, "globals", signatures)));
        return 0;
    }

    private static int lint(Options o, PrintStream out, PrintStream err) throws IOException {
        o.check(Set.of("plugin", "params", "config", "format", "output", "jobs", "dialect", "debug")); o.positionals(1);
        // text for people; json: [record, ..., summary]; jsonl: the same records, one per line
        String format = o.one("format", "text");
        if (!Set.of("text", "json", "jsonl").contains(format)) throw new IllegalArgumentException("--format must be text, json or jsonl");
        int jobs;
        try { jobs = Integer.parseInt(o.one("jobs", "1")); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("--jobs must be a number 1..256"); }
        Juno.Dialect dialect = switch (o.one("dialect", "juno")) {
            case "juno" -> Juno.Dialect.JUNO;
            case "1c" -> Juno.Dialect.PLATFORM;
            default -> throw new IllegalArgumentException("--dialect must be juno or 1c");
        };
        List<Path> files = Juno.files(Path.of(o.pos.get(0)));
        if (files.isEmpty()) throw new IllegalArgumentException("No .bsl files in " + o.pos.get(0));
        Settings settings = settings(o, Path.of(o.pos.get(0)));
        if (settings.file() != null) err.println("Настройки: " + shown(settings.file().toString()));
        List<Juno.Plugin> plugins = plugins(new Juno(), settings);
        Path report = o.has("output") ? Path.of(o.one("output", "")).toAbsolutePath().normalize() : null;
        if (report != null) {
            Path physical = Project.physical(report);
            if (App.isSource(report) || App.isSource(physical)) throw new IllegalArgumentException("Report cannot overwrite a source path");
            List<Path> protectedFiles = new ArrayList<>(files);
            Path runtime = Project.runtimePath();
            if (Files.isRegularFile(runtime)) protectedFiles.add(runtime);
            if (settings.file() != null) protectedFiles.add(settings.file());
            if (o.has("params")) protectedFiles.add(Path.of(o.one("params", "")));
            for (String spec : settings.plugins()) {
                Path plugin = Path.of(spec);
                if (!plugin.isAbsolute()) plugin = settings.base().resolve(plugin);
                protectedFiles.addAll(Juno.files(plugin));
            }
            for (Path input : protectedFiles) if (Project.sameFile(input, report))
                throw new IllegalArgumentException("Report would overwrite an input/config file: " + input);
            if (report.getParent() != null) Files.createDirectories(report.getParent());
        }
        PrintWriter writer = report != null ? new PrintWriter(Files.newBufferedWriter(report, StandardCharsets.UTF_8)) : new PrintWriter(out, true);
        int diagnostics = 0, failed = 0, crashed = 0;
        String separator = format.equals("json") ? "[\n" : "";
        try (var batch = new AnalysisBatch(files, plugins, jobs, err::println, dialect)) {
            for (Juno.FileResult r : batch) {
                diagnostics += r.diagnostics().size();
                if (r.failed()) { failed++; if (r.diagnostics().stream().anyMatch(d -> !Set.of("parse","compile").contains(d.phase()))) crashed++; }
                if (!format.equals("text")) {
                    writer.print(separator + Json.stringify(r.json()));
                    separator = format.equals("json") ? ",\n" : "\n";
                } else {
                    for (Juno.Diagnostic d : r.diagnostics()) writer.println(render(d));
                    for (var result : r.pluginResults().entrySet())
                        if (!Objects.equals(result.getValue(), Map.of("$juno", "undefined")) && !"".equals(result.getValue()))
                            writer.println(shown(r.file()) + " [" + result.getKey() + "]\n" + text(result.getValue()));
                }
            }
            if (format.equals("text")) writer.println("Файлов: " + files.size() + ", замечаний: " + diagnostics + ", файлов с ошибками: " + failed);
            else {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("type", "summary"); summary.put("files", files.size());
                summary.put("diagnostics", diagnostics); summary.put("failedFiles", failed);
                writer.println(separator + Json.stringify(summary) + (format.equals("json") ? "\n]" : ""));
            }
            writer.flush();
            if (writer.checkError()) throw new IOException("Cannot write the report");
        } finally { if (report != null) writer.close(); }
        return crashed > 0 ? 3 : diagnostics > 0 ? 1 : 0;
    }

    /** Plugins and their parameters: from the command line or from juno.json. Paths are relative to base. */
    record Settings(List<String> plugins, Map<String, Object> params, Path base, Path file) {}

    private static Settings settings(Options o, Path input) throws IOException {
        boolean command = o.has("plugin") || o.has("params");
        if (o.has("config") && command) throw new IllegalArgumentException("--config cannot be combined with --plugin or --params");
        Path file = o.has("config") ? Path.of(o.one("config", "")) : command ? null : App.projectFile(input);
        if (file == null) {
            Map<String, Object> params = o.has("params") ? Json.object(Json.parse(Files.readString(Path.of(o.one("params", ""))))) : Map.of();
            return new Settings(o.many("plugin"), params, Path.of(""), null);
        }
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("No settings file: " + file);
        Map<String, Object> json;
        try { json = Json.object(Json.parse(Files.readString(file))); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException(file + ": " + e.getMessage()); }
        ProjectManifest.validate(file, json);
        List<String> plugins = new ArrayList<>();
        for (Object item : json.containsKey("plugins") ? Json.array(json.get("plugins")) : List.of()) {
            if (!(item instanceof String name)) throw new IllegalArgumentException(file + ": plugins must be a list of strings");
            plugins.add(name);
        }
        Map<String, Object> params = json.containsKey("params") ? Json.object(json.get("params")) : Map.of();
        return new Settings(plugins, params, file.toAbsolutePath().getParent(), file);
    }

    /** A plugin is a .bsl file or a folder of them; parameters are keyed by the file name. */
    private static List<Juno.Plugin> plugins(Juno api, Settings settings) throws IOException {
        List<Path> selected = new ArrayList<>();
        for (String spec : settings.plugins()) {
            Path path = settings.base().resolve(spec);
            if (Files.isDirectory(path)) {
                try (var list = Files.list(path)) {
                    List<Path> found = list.filter(Main::isPlugin).sorted().toList();
                    if (found.isEmpty()) throw new IllegalArgumentException("No .bsl plugins in " + spec);
                    selected.addAll(found);
                }
            } else if (isPlugin(path) && Files.isRegularFile(path)) selected.add(path);
            else throw new IllegalArgumentException("No plugin file or folder: " + spec);
        }
        List<Juno.Plugin> result = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Path plugin : selected) {
            String key = plugin.getFileName().toString();
            if (!keys.add(key)) throw new IllegalArgumentException("Duplicate plugin name: " + key);
            result.add(api.plugin(plugin, settings.params().getOrDefault(key, RuntimeSupport.undefined())));
        }
        for (String key : settings.params().keySet()) if (!keys.contains(key)) throw new IllegalArgumentException("Parameters for a plugin that is not used: " + key);
        return result;
    }

    private static boolean isPlugin(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".bsl") && !Files.isDirectory(path);
    }

    /** Paths under the current folder are shown relative to it. */
    private static String shown(String file) {
        Path path = Path.of(file), here = Path.of("").toAbsolutePath();
        return path.isAbsolute() && path.startsWith(here) ? here.relativize(path).toString() : file;
    }

    private static String text(Object value) {
        return value instanceof String s ? s : Json.stringify(value);
    }

    private static boolean machine(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--format")) return !args[i + 1].equals("text");
        return false;
    }

    private static void error(String[] args, PrintStream out, PrintStream err, int code, Throwable e) {
        String message = Juno.message(e);
        String location = Juno.location(e);
        if (!location.isEmpty()) message = location + ": " + message;
        if (machine(args)) out.println(Json.stringify(Map.of("type", "error", "exitCode", code, "message", message)));
        else err.println("juno: " + message);
    }

    private static String render(Juno.Diagnostic d) {
        return shown(d.file()) + (d.line() > 0 ? ":" + d.line() + ":" + d.column() : "") + ": " + d.message() + " [" + d.source() + "]";
    }

    /** Repeated or comma-separated --define; none means no target environment. */
    static List<String> defines(List<String> values) {
        if (values.isEmpty()) return null;
        List<String> result = new ArrayList<>();
        for (String value : values) for (String symbol : value.split(",")) if (!symbol.isBlank()) result.add(symbol.strip());
        return result;
    }

    /** Options without a value. */
    static final Set<String> FLAGS = Set.of("debug","debug-sources");

    static final class Options {
        final List<String> pos = new ArrayList<>();
        final Map<String, List<String>> opts = new LinkedHashMap<>();
        Options(String[] args) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) { pos.add(arg); continue; }
                String name = arg.substring(2), value = "true";
                if (!FLAGS.contains(name)) {
                    if (++i == args.length || args[i].startsWith("--")) throw new IllegalArgumentException("Missing value for --" + name);
                    value = args[i];
                }
                opts.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            }
        }
        void check(Set<String> names) { for (String name : opts.keySet()) if (!names.contains(name)) throw new IllegalArgumentException("Unknown option: --" + name); }
        void positionals(int count) { if (pos.size() != count) throw new IllegalArgumentException("Expected " + count + " argument(s). See juno help"); }
        boolean has(String name) { return opts.containsKey(name); }
        String one(String name, String fallback) {
            List<String> values = opts.get(name);
            if (values == null) return fallback;
            if (values.size() != 1) throw new IllegalArgumentException("Option --" + name + " cannot be repeated");
            return values.get(0);
        }
        List<String> many(String name) { return opts.getOrDefault(name, List.of()); }
    }
}

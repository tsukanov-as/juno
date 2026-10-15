import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import juno.Juno;
import juno.Main;

/**
 * What tests in BSL cannot check: the command line end to end (run, build, check, test)
 * and compiled modules seen from Java. Everything else is tested in BSL: juno test tests.
 */
public final class JunoTests {
    record Result(int code, String out, String err) {}

    static Result juno(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code = Main.execute(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    static Result lint1c(String... args) {
        List<String> options=new ArrayList<>(List.of("lint")); if(!List.of(args).contains("--dialect"))options.addAll(List.of("--dialect", "1c")); options.addAll(List.of(args)); return juno(options.toArray(String[]::new));
    }

    static void check(boolean ok, Object context) { if (!ok) throw new AssertionError(String.valueOf(context)); }

    static Path write(Path dir, String name, String text) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, text);
    }

    static void testDiscovery(Path tmp) throws Exception {
        Path suite = write(tmp.resolve("discovery"), "Tests.bsl", """
            Процедура ТестУспех() Экспорт
            КонецПроцедуры
            Procedure TestSuccess() Export
            EndProcedure
            Функция ТестовыеДанные() Экспорт
                ВызватьИсключение "Функция не является тестом";
            КонецФункции
            Function TestData() Export
                Raise "A function is not a test";
            EndFunction
            Процедура ТестСкрытый()
                ВызватьИсключение "Закрытая процедура не является тестом";
            КонецПроцедуры
            Процедура ТестСПараметром(Параметр = 1) Экспорт
                ВызватьИсключение "Процедура с параметром не является тестом";
            КонецПроцедуры
            """);
        Result r = juno("test", suite.toString());
        check(r.code() == 0 && r.out().contains("Тестов: 2, упало: 0"), r);
        r = juno("test", suite.toString(), "--filter", "*.TestSuccess");
        check(r.code() == 0 && r.out().contains("Тестов: 1, упало: 0"), r);
        for (String helper : List.of("ТестовыеДанные", "TestData", "ТестСкрытый", "ТестСПараметром")) {
            r = juno("test", suite.toString(), "--filter", "*." + helper);
            check(r.code() == 2 && r.err().contains("No test procedures match"), r);
        }
    }

    static void jsonExample(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("json-example"));
        Path source = Files.copy(Path.of("examples/json/Программа.bsl"), dir.resolve("Программа.bsl"));
        Path settings = Files.copy(Path.of("examples/json/Настройки.json"), dir.resolve("Настройки.json"));
        Path input = write(dir, "input.jsonl", "{\"value\":1}\nnull\n");
        Path jar = dir.resolve("example.jar");
        Result built = juno("build", source.toString(), "--output", jar.toString());
        check(built.code() == 0, built);
        String expected = """
            {"tag":"пример","sequence":1,"data":{"value":1},"exact":12345678901234567890.12345678,"created":"2024-02-29T13:04:05"}
            {"tag":"пример","sequence":2,"data":null,"exact":12345678901234567890.12345678,"created":"2024-02-29T13:04:05"}
            """;
        for (boolean standalone : List.of(false, true)) {
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar"));
            if (standalone) {
                Files.delete(source);
                command.add(jar.toString());
            } else command.addAll(List.of(Path.of("build/juno.jar").toAbsolutePath().toString(), "run", source.toString()));
            command.add(settings.toString());
            Path out = dir.resolve("stdout.txt"), err = dir.resolve("stderr.txt");
            Process process = new ProcessBuilder(command).directory(dir.toFile()).redirectInput(input.toFile())
                .redirectOutput(out.toFile()).redirectError(err.toFile()).start();
            boolean finished = process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            check(finished, "JSON example timed out: " + command);
            String output = Files.readString(out), errors = Files.readString(err);
            check(process.exitValue() == 0 && errors.isEmpty() && output.replace("\r\n", "\n").equals(expected),
                "JSON example " + (standalone ? "JAR" : "CLI") + ": " + output + errors);
        }
    }

    static void sourceFiles(Path tmp) throws Exception {
        Path root = tmp.resolve("bsl-project");
        write(root, "juno.json", "{}");
        write(root, "Помощник.bsl", "Функция ЧислоДва() Экспорт Возврат 2; КонецФункции\n"
            + "Процедура Ошибка() Экспорт ВызватьИсключение \"ошибка помощника\"; КонецПроцедуры\n");
        write(root, "Подпапка/Расчет.BSL", "Функция Сумма() Экспорт П = Новый Помощник; Возврат П.ЧислоДва() + 40; КонецФункции\n");
        Path entry = write(root, "Программа.bsl", "Р = Новый Подпапка.Расчет; Сообщить(Р.Сумма());\n");
        Path suite = write(root, "тесты/ПростойТест.bsl", "Процедура ТестУспех() Экспорт\n"
            + "  Р = Новый Подпапка.Расчет; Если Р.Сумма() <> 42 Тогда ВызватьИсключение; КонецЕсли;\nКонецПроцедуры\n");
        Result r = juno("run", entry.toString());
        check(r.code() == 0 && r.out().strip().equals("42"), r);
        r = juno("test", root.toString());
        check(r.code() == 0 && r.out().contains("Тестов: 1, упало: 0"), r);
        check(juno("test", suite.toString()).code() == 0, "single test module");
        check(juno("lint", entry.toString()).code() == 0, "lint of the entry module");

        Path jar = tmp.resolve("bsl-program.jar");
        r = juno("build", entry.toString(), "--output", jar.toString());
        check(r.code() == 0 && Files.isRegularFile(jar), r);
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString())
            .directory(tmp.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 0 && output.strip().equals("42"), output);

        String defaultName = "JunoBsl" + tmp.getFileName().toString().replace("-", "");
        Path defaultEntry = write(root, defaultName + ".BSL", "Сообщить(42);\n");
        Path defaultJar = Path.of(defaultName + ".jar");
        try {
            r = juno("build", defaultEntry.toString());
            check(r.code() == 0 && Files.isRegularFile(defaultJar), r);
        } finally { Files.deleteIfExists(defaultJar); }

        write(root, "тесты/ПростойТест.bsl", "Процедура ТестПлохой() Экспорт\n  П = Новый Помощник;\n  П.Ошибка();\nКонецПроцедуры\n");
        r = juno("test", suite.toString());
        check(r.code() == 1 && r.out().contains("ПростойТест.bsl:3: ошибка помощника"), r);

        // The extension of another dialect is not a juno source: refused when named, not picked up from folders.
        Path foreign = write(root, "Чужой.os", "Сообщить(\"не должен выполняться\");\n");
        r = juno("run", foreign.toString());
        check(r.code() == 2 && r.err().contains("Expected .bsl file") && r.out().isEmpty(), r);
        write(root, "Использует.bsl", "Х = Новый Чужой;\n");
        r = juno("run", root.resolve("Использует.bsl").toString());
        check(r.code() != 0 && (r.err() + r.out()).contains("Чужой"), r);

        // Module names are compared without case, so two files that differ only in case clash.
        Path collision = tmp.resolve("case-collision");
        Path first = write(collision, "Модуль.bsl", "");
        if (!Files.exists(collision.resolve("модуль.bsl"))) { // a case-insensitive file system cannot hold both
            write(collision, "модуль.bsl", "");
            r = juno("run", first.toString());
            check(r.code() == 2 && r.err().contains("Duplicate module name") && r.err().contains("модуль.bsl"), r);
        }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("juno-tests-");
        try {
        sourceFiles(tmp);
        testDiscovery(tmp);
        jsonExample(tmp);

        Result r = juno("run", "examples/привет/Привет.bsl");
        check(r.code() == 0 && r.out().equals("Привет, мир!\nПривет, JVM!\n".replace("\n", System.lineSeparator())), r);

        r = juno("run", "examples/магазин/Программа.bsl");
        check(r.code() == 0 && r.out().contains("Хлеб по 45.5 руб. × 2") && r.out().contains("Итого: 180.9"), r);

        // A built program is a plain JAR: no juno needed to run it.
        Path jar = tmp.resolve("магазин.jar");
        r = juno("build", "examples/магазин/Программа.bsl", "--output", jar.toString());
        check(r.code() == 0 && Files.isRegularFile(jar), r);
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", jar.toString())
            .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 0 && output.contains("Итого: 180.9"), output);

        // The example plugins: a folder of plugins.
        r = juno("lint", "examples/проверка", "--plugin", "plugins", "--dialect", "1c");
        check(r.code() == 1, r);
        check(r.out().contains("Модуль.bsl:9:13: Запрос выполняется в цикле [ЗапросВЦикле]"), r);
        check(r.out().contains("Модуль.bsl:16:2: Пустой блок Исключение"), r);
        check(r.out().contains("Модуль.bsl:21:7: Модальный вызов Вопрос(): используйте ВопросАсинх"), r);
        check(r.out().contains("Модуль.bsl:24:3: Ошибка теряется"), r);
        check(r.out().contains("замечаний: 4"), r);
        r = lint1c("examples/привет", "--plugin", "plugins");
        check(r.code() == 0 && r.out().contains("замечаний: 0"), r);

        // No false alarms: a query outside a loop, an own method named Вопрос, a handled exception.
        Path clean = write(tmp, "clean.bsl", """
            Процедура Вопрос(Текст)
            КонецПроцедуры
            Процедура П(Список)
                Запрос = Новый Запрос;
                Результат = Запрос.Выполнить();
                Для Каждого Элемент Из Список Цикл
                    Вопрос(Элемент);
                    Попытка
                        Элемент.Записать();
                    Исключение
                        ЗаписьЖурналаРегистрации("Запись", УровеньЖурналаРегистрации.Ошибка, , , ОписаниеОшибки());
                    КонецПопытки;
                КонецЦикла;
            КонецПроцедуры
            """);
        r = lint1c(clean.toString(), "--plugin", "plugins");
        check(r.code() == 0, r);

        // Parameters: an own logging procedure counts as handling the error.
        Path logged = write(tmp, "logged.bsl", "Попытка\n  Сделать();\nИсключение\n  Логи.Записать();\nКонецПопытки;\n");
        check(lint1c(logged.toString(), "--plugin", "plugins/ПодавлениеИсключений.bsl").code() == 1, "own logger unknown");
        Path params = write(tmp, "params.json", "{\"ПодавлениеИсключений.bsl\": {\"Обработчики\": \"Логи.Записать\"}}");
        r = lint1c(logged.toString(), "--plugin", "plugins/ПодавлениеИсключений.bsl", "--params", params.toString(), "--format", "json");
        check(r.code() == 0 && r.out().contains("\"type\":\"summary\""), r);
        Result one = lint1c("examples", "--plugin", "plugins", "--format", "jsonl");
        Result four = lint1c("examples", "--plugin", "plugins", "--format", "jsonl", "--jobs", "4");
        check(one.out().equals(four.out()) && one.code() == four.code(), four);
        // jsonl: one object per line; json: the same objects as one array.
        List<String> lines = one.out().lines().toList();
        for (String line : lines) check(juno.Json.parse(line) instanceof Map<?, ?>, line);
        Object array = juno.Json.parse(lint1c("examples", "--plugin", "plugins", "--format", "json").out());
        check(array instanceof List<?> list && list.size() == lines.size()
            && ((Map<?, ?>) list.get(list.size() - 1)).get("type").equals("summary"), array);
        check(lint1c("examples", "--format", "xml").code() == 2, "unknown format");

        // A plugin of your own, from a file.
        Path plugin = write(tmp, "Names.bsl", """
            Перем Имена, Префикс;
            Процедура Открыть(Парсер, Параметры) Экспорт
                Имена = Новый Массив;
                Префикс = Параметры.Префикс;
            КонецПроцедуры
            Функция Закрыть() Экспорт
                Возврат СтрСоединить(Имена, ",");
            КонецФункции
            Функция Подписки() Экспорт
                Подписки = Новый Массив;
                Подписки.Добавить("ПосетитьОбъявлениеМетода");
                Возврат Подписки;
            КонецФункции
            Процедура ПосетитьОбъявлениеМетода(Объявление) Экспорт
                Имена.Добавить(Префикс + Объявление.Сигнатура.Имя);
            КонецПроцедуры
            """);
        Path names = write(tmp, "names.json", "{\"Names.bsl\": {\"Префикс\": \"#\"}}");
        r = lint1c("examples/проверка", "--plugin", plugin.toString(), "--params", names.toString());
        check(r.code() == 0 && r.out().contains("#ОбновитьЦены,#Сохранить,#Удалить"), r);

        // A folder of plugins, and juno.json found next to or above the checked folder.
        Path project = tmp.resolve("проект");
        write(project, "src/Модуль.bsl", "Процедура А()\nКонецПроцедуры\nПроцедура Б()\n  Вопрос(1);\nКонецПроцедуры\n");
        Files.createDirectories(project.resolve("проверки"));
        Files.copy(plugin, project.resolve("проверки/Names.bsl"));
        write(project, "проверки/Count.bsl", """
            Перем Счет;
            Процедура Открыть(Парсер, Параметры) Экспорт Счет = 0; КонецПроцедуры
            Функция Закрыть() Экспорт Возврат "методов: " + Счет; КонецФункции
            Функция Подписки() Экспорт
                Подписки = Новый Массив; Подписки.Добавить("ПосетитьОбъявлениеМетода"); Возврат Подписки;
            КонецФункции
            Процедура ПосетитьОбъявлениеМетода(Объявление) Экспорт Счет = Счет + 1; КонецПроцедуры
            """);
        String src = project.resolve("src").toString();
        r = lint1c(src, "--plugin", project.resolve("проверки").toString(), "--params", names.toString());
        check(r.code() == 0 && r.out().contains("#А,#Б") && r.out().contains("методов: 2"), r);
        String modal = Path.of("plugins/МодальныеВызовы.bsl").toAbsolutePath().toString().replace('\\', '/');
        write(project, "juno.json", "{\"plugins\": [\"" + modal + "\", \"проверки\"], \"params\": {\"Names.bsl\": {\"Префикс\": \"!\"}}}");
        r = lint1c(src);
        check(r.code() == 1 && r.out().contains("!А,!Б") && r.out().contains("Модальный вызов Вопрос()") && r.err().contains("juno.json"), r);
        r = lint1c(src, "--plugin", "plugins/ЗапросВЦикле.bsl");
        check(r.code() == 0 && !r.out().contains("!А"), "command line wins over juno.json: " + r);
        r = lint1c("examples/привет", "--config", project.resolve("juno.json").toString());
        check(r.code() == 0 && r.out().contains("!Приветствие"), r);
        check(lint1c(src, "--config", project.resolve("juno.json").toString(), "--plugin", "plugins").code() == 2, "--config with --plugin");
        write(project, "juno.json", "{\"plugin\": [\"проверки\"]}");
        r = lint1c(src);
        check(r.code() == 2 && r.err().contains("unknown key"), r);
        Files.delete(project.resolve("juno.json"));

        // Syntax errors are diagnostics, not crashes; platform async syntax is understood.
        Path bad = write(tmp, "bad.bsl", "Процедура П()\n  Если Тогда\nКонецПроцедуры\n");
        r = lint1c(bad.toString());
        check(r.code() == 1 && r.out().contains("bad.bsl:2:") && r.out().contains("файлов с ошибками: 1"), r);
        Path async = write(tmp, "async.bsl", "Асинх Процедура П()\n  Ждать П();\nКонецПроцедуры\nПроцедура Ж()\n  Ждать = 1;\nКонецПроцедуры\n");
        check(lint1c(async.toString()).code() == 0, "async syntax");

        // Preprocessor: the target environment is chosen at compile time.
        Path env = write(tmp, "env/env.bsl", "#Если Сервер Тогда\nСообщить(\"сервер\");\n#Иначе\nСообщить(\"клиент\");\n#КонецЕсли\n");
        r = juno("run", "--define", "Сервер", env.toString());
        check(r.code() == 0 && r.out().strip().equals("сервер"), r);
        r = juno("run", "--define", "Клиент", env.toString());
        check(r.code() == 0 && r.out().strip().equals("клиент"), r);

        // juno test: a failed test names the line of the test, not of the helper that raised.
        Path suite = tmp.resolve("тесты");
        write(suite, "Помощник.bsl", "Процедура Равно(А, Б) Экспорт\n  Если А <> Б Тогда ВызватьИсключение \"не равно\"; КонецЕсли;\nКонецПроцедуры\n");
        write(suite, "Модуль.bsl", "Перем П;\nПроцедура ТестХороший() Экспорт\n  П.Равно(1, 1);\nКонецПроцедуры\n"
            + "Процедура ТестПлохой() Экспорт\n  П.Равно(1, 2);\nКонецПроцедуры\nП = Новый Помощник;\n");
        r = juno("test", suite.toString());
        check(r.code() == 1 && r.out().contains("✗ Модуль.ТестПлохой: Модуль.bsl:6: не равно") && r.out().contains("Тестов: 2, упало: 1"), r);
        check(juno("test", "examples/магазин").code() == 0, "example tests");
        r = juno("test", suite.resolve("Помощник.bsl").toString());
        check(r.code() == 2, "a module without tests: " + r);

        // juno.json marks the root: any module of the project can be started, names stay the same.
        Path root = tmp.resolve("корень");
        write(root, "Справочники/Товар.bsl", "Функция Имя() Экспорт\n  Возврат \"товар\";\nКонецФункции\n");
        Path debug = write(root, "Справочники/Отладка.bsl", "Т = Новый Справочники.Товар;\nСообщить(Т.Имя());\n");
        write(root, "тесты/ТоварТест.bsl", "Процедура ТестИмя() Экспорт\n  Т = Новый Справочники.Товар;\nКонецПроцедуры\n");
        check(juno("run", debug.toString()).code() == 3, "without juno.json the root is the folder of the file");
        write(root, "juno.json", "{}");
        r = juno("run", debug.toString());
        check(r.code() == 0 && r.out().strip().equals("товар"), r);
        r = juno("test", root.resolve("тесты/ТоварТест.bsl").toString());
        check(r.code() == 0 && r.out().contains("Тестов: 1, упало: 0"), r);

        // Command-line arguments and the exit code: juno options before the program, its arguments after.
        Path script = write(tmp, "аргументы/скрипт.bsl", "Для Каждого А Из АргументыКоманднойСтроки Цикл\n  Сообщить(А);\nКонецЦикла;\n"
            + "Попытка\n  ЗавершитьРаботу(АргументыКоманднойСтроки.Количество());\nИсключение\n  Сообщить(\"перехвачено\");\nКонецПопытки;\nСообщить(\"дальше\");\n");
        r = juno("run", "--define", "Сервер", script.toString(), "один", "два три", "--флаг");
        check(r.code() == 3 && r.out().equals(String.join(System.lineSeparator(), "один", "два три", "--флаг", "")), r);
        Path scriptJar = tmp.resolve("аргументы.jar");
        check(juno("build", script.toString(), "--output", scriptJar.toString()).code() == 0, "build script");
        process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", scriptJar.toString(), "а", "б")
            .redirectErrorStream(true).start();
        output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        check(process.waitFor() == 2 && output.lines().toList().equals(List.of("а", "б")), output);

        // Endless recursion is reported in one line with the place in the code, without a JVM stack trace.
        Path loop = write(tmp, "цикл/Главный.bsl", "Х = Новый А;\n");
        write(tmp, "цикл/А.bsl", "Перем Б;\nБ = Новый Б;\n");
        write(tmp, "цикл/Б.bsl", "Перем А;\nА = Новый А;\n");
        r = juno("run", loop.toString());
        check(r.code() == 3 && r.err().contains("слишком глубокая рекурсия") && !r.err().contains("at java."), r);
        write(tmp, "цикл/ГлубинаТест.bsl", "Процедура ТестБезКонца() Экспорт\n  Х = Новый А;\nКонецПроцедуры\n");
        r = juno("test", tmp.resolve("цикл").toString());
        check(r.code() == 1 && r.out().contains("✗ ГлубинаТест.ТестБезКонца") && r.out().contains("слишком глубокая рекурсия"), r);

        // A compiled module is an ordinary Java class: exported members are public.
        byte[] bytes = new Juno().compile("Перем Итог Экспорт;\nПерем Скрыто;\nФункция Сумма(А, Б) Экспорт\n  Возврат А + Б;\nКонецФункции\n", "Calc.bsl", "demo.Calc");
        var loader = new ClassLoader(JunoTests.class.getClassLoader()) { Class<?> load() { return defineClass("demo.Calc", bytes, 0, bytes.length); } };
        Class<?> type = loader.load();
        Object calc = type.getConstructor().newInstance();
        check(type.getMethod("Сумма", Object.class, Object.class).invoke(calc, new BigDecimal("40"), new BigDecimal("2")).equals(new BigDecimal("42")), "public method");
        check(Modifier.isPublic(type.getField("Итог").getModifiers()), "public field");
        check(Modifier.isPrivate(type.getDeclaredField("Скрыто").getModifiers()), "private field");

        // Usage errors.
        check(lint1c("examples", "--colour", "red").code() == 2, "unknown option");
        check(lint1c("examples", "--plugin", "no-such-plugin.bsl").code() == 2, "no plugin file");

        // Dialect: juno syntax by default, --dialect 1c accepts only the platform language.
        Path dotted = write(tmp, "dotted.bsl", "Товар = Новый Справочники.Товар;\n");
        write(tmp, "Справочники/Товар.bsl", "");
        check(juno("lint", dotted.toString()).code() == 0, "juno syntax by default");
        check(lint1c(dotted.toString(), "--dialect", "1c").code() == 1, "platform dialect");
        check(juno("frobnicate").code() == 2, "unknown command");
        check(juno("version").out().startsWith("juno "), "version");

        System.out.println("Command line PASS");
        } finally {try(var files=Files.walk(tmp)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}

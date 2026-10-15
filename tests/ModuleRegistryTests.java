import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import juno.Main;
import juno.runtime.*;
import static juno.runtime.RuntimeSupport.*;

/** Shared module lifetime, discovery and isolation through the CLI and standalone JAR. */
public final class ModuleRegistryTests {
    record Result(int code, String out, String err) {}
    static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    static void check(boolean value, Object detail) { if (!value) throw new AssertionError(detail); }
    static Path write(Path root, String name, String source) throws IOException {
        Path path = root.resolve(name); Files.createDirectories(path.getParent());
        return Files.writeString(path, source);
    }
    static Result cli(String... args) {
        var out = new ByteArrayOutputStream(); var err = new ByteArrayOutputStream();
        int code = Main.execute(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Result(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
    static void success(Result result) { check(result.code == 0, result); }
    static void delete(Path root) throws IOException {
        try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
    }
    static void sameInJar(Path root, Path project, String expected) throws Exception {
        for (int i = 0; i < 2; i++) {
            Result run = cli("run", project.toString());
            check(run.code == 0 && run.out.strip().equals(expected), run);
        }
        Path jar = root.resolve("application.jar");
        success(cli("build", project.toString(), "--output", jar.toString()));
        delete(Files.isDirectory(project) ? project : project.getParent());
        if (Files.exists(root.resolve("library"))) delete(root.resolve("library"));
        Path stdout = root.resolve("stdout.txt"), stderr = root.resolve("stderr.txt");
        Process process = new ProcessBuilder(JAVA, "-jar", jar.toString()).directory(root.toFile())
            .redirectOutput(stdout.toFile()).redirectError(stderr.toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(); throw new AssertionError("JAR timeout"); }
        check(process.exitValue() == 0 && Files.readString(stdout).strip().equals(expected), Files.readString(stderr) + Files.readString(stdout));
    }
    static void project(Path root) throws Exception {
        Path project = root.resolve("project");
        write(project, "juno.json", """
            {"schemaVersion":1,"entry":"src/Main.bsl","sources":["src"],"tests":["tests"],
             "dependencies":[{"path":"../library","namespace":"Shared"}]}
            """);
        write(root, "library/juno.json", "{\"schemaVersion\":1,\"namespace\":\"Shared\",\"sources\":[\"src\"]}");
        write(root, "library/src/Lib.bsl", "Перем Значение Экспорт; Значение = 2026;");
        write(project, "src/Helpers/Counter.bsl", """
            Перем Значение Экспорт;
            Процедура ПриСозданииОбъекта(Добавка = 1)
                Значение = Значение + Добавка;
            КонецПроцедуры
            Значение = 10;
            Раз = ПолучитьПеременнуюСреды("JUNO_REGISTRY_BODIES");
            Если Раз = Неопределено Тогда Раз = "0"; КонецЕсли;
            УстановитьПеременнуюСреды("JUNO_REGISTRY_BODIES", Строка(Число(Раз) + 1));
            """);
        write(project, "src/Cycle/A.bsl", "Б = Модули.Получить(\"Cycle.B\");");
        write(project, "src/Cycle/B.bsl", "А = Modules.Get(\"cYcLe.a\");");
        write(project, "src/Required.bsl", "Процедура ПриСозданииОбъекта(Параметр) КонецПроцедуры");
        write(project, "src/Flaky.bsl", """
            Перем Значение Экспорт;
            Процедура ПриСозданииОбъекта()
                Если ПолучитьПеременнуюСреды("JUNO_REGISTRY_RETRY") <> "ready" Тогда
                    УстановитьПеременнуюСреды("JUNO_REGISTRY_RETRY", "ready");
                    ВызватьИсключение "initialization failed";
                КонецЕсли;
                Значение = 17;
            КонецПроцедуры
            """);
        write(project, "src/Worker.bsl", """
            Функция Проверить() Экспорт
                А = Новый Утверждения;
                Счетчик = Modules.Get("Helpers.Counter");
                А.Равно(Счетчик.Значение, 11);
                Счетчик.Значение = 12;
                А.Равно(Модули.Получить("helpers.counter").Значение, 12);
                Возврат Счетчик.Значение;
            КонецФункции
            """);
        write(project, "tests/Isolation.bsl", """
            Процедура ТестПервый() Экспорт Проверить(); КонецПроцедуры
            Процедура ТестВторой() Экспорт Проверить(); КонецПроцедуры
            Процедура Проверить()
                А = Новый Утверждения;
                Счетчик = Модули.Получить("Helpers.Counter");
                А.Равно(Счетчик.Значение, 11);
                Счетчик.Значение = 99;
            КонецПроцедуры
            """);
        write(project, "src/Main.bsl", """
            Процедура Ошибка(Номер, Фрагмент)
                Попытка
                    Если Номер = 1 Тогда Модули.Получить("Missing");
                    ИначеЕсли Номер = 2 Тогда Модули.Получить(42);
                    ИначеЕсли Номер = 3 Тогда Модули.Содержит(Неопределено);
                    ИначеЕсли Номер = 4 Тогда Модули.Получить();
                    ИначеЕсли Номер = 5 Тогда Модули.Имена(1);
                    ИначеЕсли Номер = 6 Тогда Модули.Содержит("Main", 1);
                    ИначеЕсли Номер = 7 Тогда Модули.Получить("Required");
                    ИначеЕсли Номер = 8 Тогда Модули.Получить("Cycle.A");
                    ИначеЕсли Номер = 9 Тогда Модули.Получить("Flaky");
                    ИначеЕсли Номер = 10 Тогда Модули.НеизвестныйМетод();
                    ИначеЕсли Номер = 11 Тогда Модули.Имена().Добавить("extra");
                    ИначеЕсли Номер = 12 Тогда Объект = Новый Модули;
                    ИначеЕсли Номер = 13 Тогда
                        П = Новый Массив; П.Добавить(Модули);
                        ФоновыеЗадания.Выполнить("Worker.Проверить", П);
                    КонецЕсли;
                Исключение
                    Если СтрНайти(ОписаниеОшибки(), Фрагмент) = 0 Тогда ВызватьИсключение; КонецЕсли;
                    Возврат;
                КонецПопытки;
                ВызватьИсключение "Ожидалась ошибка " + Строка(Номер);
            КонецПроцедуры
            А = Новый Утверждения;
            А.Истина(Модули = Modules);
            А.Истина(ТипЗнч(Модули) = Тип("Modules"));
            А.Равно(Строка(Модули), "Модули");
            УстановитьПеременнуюСреды("JUNO_REGISTRY_BODIES", "0");
            А.Истина(Modules.Contains("HELPERS.COUNTER"));
            А.Ложь(Модули.Содержит("Missing"));
            А.Ложь(Модули.Содержит("Парсер"));
            А.Равно(СтрСоединить(Модули.Имена(), ","), "Cycle.A,Cycle.B,Flaky,Helpers.Counter,Main,Required,Shared.Lib,Worker");
            А.Равно(ПолучитьПеременнуюСреды("JUNO_REGISTRY_BODIES"), "0");
            Счетчик = Модули.Получить("Helpers.Counter");
            А.Равно(Счетчик.Значение, 11);
            Счетчик.Значение = 41;
            А.Истина(Счетчик = Modules.Get("HELPERS.COUNTER"));
            А.Равно(ПолучитьПеременнуюСреды("JUNO_REGISTRY_BODIES"), "1");
            Другой = Новый Helpers.Counter;
            А.Равно(Другой.Значение, 11);
            А.Равно(Счетчик.Значение, 41);
            А.Равно(ПолучитьПеременнуюСреды("JUNO_REGISTRY_BODIES"), "2");
            Часть = "Shared.";
            А.Равно(Модули.Получить(Часть + "Lib").Значение, 2026);
            Ошибка(1, "Неизвестный модуль"); Ошибка(2, "строкой"); Ошибка(3, "пропущен");
            Ошибка(4, "аргументов"); Ошибка(5, "аргументов"); Ошибка(6, "аргументов");
            Ошибка(7, "Не хватает параметров");
            Ошибка(8, "Cycle.A → Cycle.B → Cycle.A");
            Ошибка(8, "Циклическая инициализация");
            УстановитьПеременнуюСреды("JUNO_REGISTRY_RETRY", Неопределено);
            Ошибка(9, "initialization failed");
            А.Равно(Модули.Получить("Flaky").Значение, 17);
            А.Истина(Модули.Получить("Flaky") = Modules.Get("flaky"));
            Ошибка(10, "неподдерживаемый метод"); Ошибка(11, "Fixed array");
            Ошибка(12, "глобальное свойство"); Ошибка(13, "параметр фонового задания");
            Первый = ФоновыеЗадания.Выполнить("Worker.Проверить");
            Второй = ФоновыеЗадания.Выполнить("Worker.Проверить");
            А.Равно(Первый.ПолучитьРезультат(), 12);
            А.Равно(Второй.ПолучитьРезультат(), 12);
            А.Равно(Счетчик.Значение, 41);
            А.Равно(ПолучитьПеременнуюСреды("JUNO_REGISTRY_BODIES"), "2");
            Сообщить("modules OK");
            """);
        Result tests = cli("test", project.toString());
        check(tests.code == 0 && tests.out.contains("Тестов: 2, упало: 0"), tests);
        sameInJar(root, project, "modules OK");
    }
    static void reachable(Path root) throws Exception {
        Path project = root.resolve("script");
        write(project, "Counter.bsl", "Перем Значение Экспорт; Значение = 7;");
        write(project, "Unused.bsl", "Процедура Сломано(");
        write(project, "Main.bsl", """
            А = Новый Утверждения;
            А.Равно(Modules.Get("Counter").Значение, 7);
            А.Равно(СтрСоединить(Modules.Names(), ","), "Counter,Main");
            Сообщить("script OK");
            """);
        // A script's assembly is selected by its literal module references, including registry names.
        sameInJar(root, project.resolve("Main.bsl"), "script OK");
    }
    static void fails(Runnable action, String message) {
        try { action.run(); } catch (BslException e) { check(e.getMessage().contains(message), e); return; }
        throw new AssertionError("Expected " + message);
    }
    static void ownership() {
        Object[] registry = new Object[1];
        try (var context = ApplicationContext.system(); var scope = Execution.withContext(context);
             var app = new BackgroundJobs(Map.of(), 1, 1, s -> {})) {
            app.run(() -> {
                registry[0] = globalValue("modules");
                fails(() -> ValueCopy.snapshot(registry[0]), "параметр фонового задания");
                try (var child = Execution.context().snapshot(); var nested = Execution.withContext(child)) {
                    fails(() -> call(registry[0], "Names", new Object[0]), "другому контексту");
                    check(globalValue("modules") != registry[0], "a snapshot has its own registry");
                }
                check(globalValue("modules") == registry[0], "nested contexts restore their parent registry");
                return UNDEFINED;
            });
        }
        fails(() -> call(registry[0], "Names", new Object[0]), "закрыт");
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("juno-module-registry-");
        try {
            project(root); reachable(root); ownership();
            Result sample = cli("run", "examples/модули/Программа.bsl");
            check(sample.code == 0 && sample.out.lines().toList().equals(List.of(
                "[1] Программа запущена", "[2] Работа выполнена", "Записей: 2")), sample);
        }
        finally { delete(root); }
        System.out.println("Module registry PASS");
    }
}

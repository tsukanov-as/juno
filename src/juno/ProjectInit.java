package juno;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Non-overwriting starter, ready for run/test/build without third-party libraries. */
final class ProjectInit {
    static void create(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Map<String,String> files = new LinkedHashMap<>();
        files.put("juno.json","""
            {"schemaVersion":1,"entry":"src/Программа.bsl","sources":["src"],"tests":["tests"],"exclude":[],"resources":[{"path":"assets","prefix":""}],"dependencies":[],"defines":[],"requires":{"jdk":21}}
            """);
        files.put("src/Приветствие.bsl","""
            Функция Текст(Имя) Экспорт
                Возврат "Привет, " + Имя + "!";
            КонецФункции
            """);
        files.put("src/Программа.bsl","""
            Имя = ?(АргументыКоманднойСтроки.Количество()=0,"мир",АргументыКоманднойСтроки[0]);
            Помощник = Новый Приветствие;
            Сообщить(Помощник.Текст(Имя));
            Сообщить(ПолучитьТекстРесурса("info.txt"));
            """);
        files.put("assets/info.txt","Ресурс приложения\n");
        files.put("tests/Smoke.bsl","""
            Процедура ТестПриветствие() Экспорт
                А=Новый Утверждения;
                Помощник=Новый Приветствие;
                А.Равно(Помощник.Текст("мир"),"Привет, мир!");
                А.Содержит(ПолучитьТекстРесурса("info.txt"),"Ресурс");
                Каталог=Новый ВременныйКаталог;
                ФайлКаталога=Новый Файл(Каталог.Путь);
                А.Истина(ФайлКаталога.ЭтоКаталог());
                Каталог.Закрыть();
            КонецПроцедуры
            """);
        files.put("README.md","""
            # Проект juno

            Требуются JDK 21+ и juno. Сборка и запуск работают без внешних зависимостей и сети.

            ```sh
            juno run .
            juno test .
            juno test . --filter '*Приветствие*' --format json
            juno build . --output app.jar
            java -jar app.jar
            ```

            src — исходники приложения; tests компилируются только командой test.
            assets перечислены явно в juno.json и читаются через GetResource/GetResourceText.
            Локальные библиотеки: dependencies:[{"path":"../library","namespace":"Shared"}].
            В библиотеке задайте schemaVersion:1, namespace:"Shared", sources:["src"].
            Вызовите Новый Shared.Helper; внутренние вызовы также fully qualified.
            """);
        files.put(".gitignore","build/\n*.jar\n");
        // Native filenames must be representable even under a Linux LANG=C JVM.
        String nativeCharset=System.getProperty("sun.jnu.encoding",java.nio.charset.Charset.defaultCharset().name());
        if(!java.nio.charset.Charset.forName(nativeCharset).newEncoder().canEncode("Программа")) {
            Map<String,String> portable=new LinkedHashMap<>();
            files.forEach((name,text)->portable.put(name.replace("Программа","Main").replace("Приветствие","Greeting"),text.replace("Программа","Main").replace("Приветствие","Greeting")));
            files=portable;
        }
        for (String name : files.keySet()) {
            Path target = root.resolve(name);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS))
                throw new IllegalArgumentException("init would overwrite " + target);
            for (Path parent = target.getParent(); parent != null && parent.startsWith(root); parent = parent.getParent())
                if (Files.isSymbolicLink(parent)) throw new IllegalArgumentException("init symlink directory: " + parent);
        }
        for (var file : files.entrySet()) {
            Path target = root.resolve(file.getKey());
            Files.createDirectories(target.getParent());
            Files.writeString(target, file.getValue(), StandardOpenOption.CREATE_NEW);
        }
    }
}

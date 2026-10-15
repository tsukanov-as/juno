Перем Проверка;

Процедура ТестТипыКонсолиИПотока() Экспорт
    // Compiler resolves Type constants before first use of the global objects.
    ТКонсоль = Тип("Console"); ТЦель = Тип("EnvironmentVariableTarget"); ТПоток = Тип("Stream");
    Проверка.Равно(ТипЗнч(Консоль), ТКонсоль);
    Проверка.Равно(Консоль, Console);
    Проверка.Равно(ТипЗнч(EnvironmentVariableTarget.Process), ТЦель);
    Проверка.Равно(EnvironmentVariableTarget.Process, РасположениеПеременнойСреды.Процесс);
    Поток = Консоль.ОткрытьСтандартныйПотокВывода();
    Проверка.Равно(ТипЗнч(Поток), ТПоток);
    Проверка.Проверить(Поток.CanWrite И Не Поток.ДоступноЧтение И Не Поток.CanSeek);
    Поток.Close(); Поток.Закрыть();
    Проверка.Проверить(Не Поток.CanWrite);
    Проверка.Равно(Консоль.КодировкаВходногоПотока, TextEncoding.UTF8NoBOM);
    Консоль.КодировкаВыходногоПотока = "windows-1251";
    Проверка.Равно(Console.OutputEncoding, КодировкаТекста.ANSI);
    Console.OutputEncoding = TextEncoding.UTF8;
    Проверка.Равно(Console.OutputEncoding, TextEncoding.UTF8NoBOM);
КонецПроцедуры

Процедура ТестСнимокОкружения() Экспорт
    Имя = "JUNO_TEST_ENV_CONSOLE";
    Проверка.Равно(GetEnvironmentVariable(Имя), Неопределено);
    УстановитьПеременнуюСреды(Имя, "Привет");
    Проверка.Равно(GetEnvironmentVariable(Имя, EnvironmentVariableTarget.Process), "Привет");
    Снимок = EnvironmentVariables();
    Проверка.Равно(Снимок[Имя], "Привет");
    Снимок.Вставить(Имя, "другое");
    Проверка.Равно(ПолучитьПеременнуюСреды(Имя), "Привет");
    SetEnvironmentVariable(Имя, "");
    Проверка.Равно(ПолучитьПеременнуюСреды(Имя), Неопределено);
    SetEnvironmentVariable(Имя, "A"); SetEnvironmentVariable(Имя, Неопределено);
    Проверка.Равно(ПеременныеСреды()[Имя], Неопределено);
КонецПроцедуры

Проверка = Новый Проверка;

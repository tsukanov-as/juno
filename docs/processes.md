# Внешние процессы

Процессы запускаются `ProcessBuilder(List<String>)` на JDK21. Сигнатуры и обычное поведение
сверены с OneScript 2.2.1; поддерживается описанный ниже поднабор API.
Нет неявного shell, изменения System streams, process-global cwd/env или чтения host stdin.

## Запуск

```bsl
ЗапуститьПриложение(Команда, Каталог = "", Ждать = Ложь, КодВозврата = Неопределено);
// EN: RunApp(command, currentDir, wait, exitCode)
Код=99;
RunApp("tool --check", "", Истина, Код);
```

При Ждать=true выходной параметр получает exit code, включая ненулевой. Поддержаны переменная,
свойство, индекс и локальный `Знач`-параметр (меняется его копия). Пропуск/Неопределено допустим;
прочий literal вместо выходной переменной — ошибка. При false параметр остаётся прежним.
Процедура возвращает Неопределено. Child stdout/stderr передаются соответствующим **host streams контекста**,
точными bytes без BOM/перекодирования. Host Console.OutputEncoding на эти bytes не влияет.
Ошибки spawn/command/cwd — исключения с причиной/описанием, а не exit code; nonzero child — обычный код.

`СоздатьПроцесс/CreateProcess(Команда, Каталог = "", ПерехватыватьВывод = Ложь,
ПерехватыватьВвод = Ложь, Кодировка = Неопределено, Среда = Неопределено)` возвращает
**незапущенный** `Процесс/Process`. `Новый Процесс` не поддерживается.

Массив argv в OS CreateProcess не работает: он превращался в имя executable «Массив».
Для него есть отдельно названное **расширение juno** с теми же optional args:

```bsl
А=Новый Массив; А.Добавить("tool"); А.Добавить("a b"); А.Добавить(""); А.Добавить("Я");
П=СоздатьПроцессСАргументами(А,"",Истина,Истина);
// EN: CreateProcessWithArguments(argv, currentDir, redirectOutput, redirectInput, encoding, env)
П.Запустить();
```

argv содержит executable в элементе0; каждый элемент String, пустые аргументы сохраняются.
Пустой executable, NUL в любом аргументе, неверные types/arity отвергаются. Array/FixedArray
копируется при Create, без связи с поздними изменениями источника. Для динамических аргументов
предпочитайте этот API: не собирайте строки с ручным quoting.

### Command line и поиск executable

Unix: whitespace разделяет аргументы; одинарные/двойные кавычки группируют и удаляются.
Внутри single quotes всё буквально. В double quotes backslash экранирует `"`/`\`;
снаружи также пробел и single quote. В остальных случаях backslash сохраняется.
Пустые quoted args сохраняются, незакрытая кавычка — ошибка. `$HOME`, `~`, `*`, `;`, `|`, `>`
не раскрываются и не выполняют команды. OS тоже не раскрывал shell syntax, но терял `""` и
иначе обрабатывал некоторые escaped quotes; эти ошибки juno не копирует.

Windows: space/TAB и double quotes; отдельные правила argv0, чётные/нечётные backslashes
перед quote, doubled quote внутри quoted arg — [CRT rules Microsoft](https://learn.microsoft.com/en-us/cpp/c-language/parsing-c-command-line-arguments?view=msvc-170).
Незакрытые quotes явно отвергаются. Single quote — обычный символ.
Нестандартный native parser конкретного executable может отличаться от CRT.
При передаче argv в стандартном режиме JDK на Windows juno экранирует встроенные
кавычки и обратные слеши перед ними по CRT: аргумент `a"b` остаётся одним аргументом
с буквальной кавычкой. Явный `cmd.exe /c` использует собственные правила оболочки.

Executable с разделителем пути разрешается через **child cwd**, абсолютный путь сохраняется.
Basename ищется самим juno через snapshot PATH: JDK иначе использует PATH родительской JVM при поиске.
Unix: если PATH отсутствует, `/usr/bin:/bin`; пустые/относительные элементы PATH идут через child cwd.
Windows: cwd, затем PATH без учёта регистра; для basename без расширения проверяются `.exe`, `.com`
в этом порядке. PATHEXT не раскрывает скрипты/ассоциации; не используется ambient JVM search.
Прямые `.bat/.cmd` отвергаются: нужен [явный cmd.exe /c](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw).
На Unix скрипт должен иметь executable bit и корректный shebang. Shell при необходимости задаётся
явно: argv `['/bin/sh','-c', script]` или `['cmd.exe','/c',script]`; его безопасность — задача вызывающего кода.

### cwd и среда

Каталог `""`/Неопределено выбирает cwd приложения **в момент Start**, явный относительный каталог
разрешается тогда же. Он должен существовать. Environment также snapshot при Start;
поздние изменения родителя не меняют работающий child. Это соответствует наблюдённому OS.

Среда optional — Map/FixedMap строковых имён/значений, **overlay snapshot при Create** поверх
context environment при Start. Пустая строка передаётся как пустое значение, не удаление;
имя не пустое, без `=`/NUL, значение без NUL. Нет неявного `System.getenv()` вместо contextenv.
На Windows keys без регистра, включая PATH/Path, Unix case-sensitive.
Фоновое задание уже получает собственный cwd/env snapshot от приложения при запуске задания.

На Unix JDK кодирует argv/cwd/env через native encoding **host JVM** (sun.jnu.encoding);
juno заранее проверяет представимость и отвергает потерю символов вместо Java replacement.
Для Unicode нужен UTF-8 locale при запуске самой JVM. Locale/декодирование аргументов **child**
задаётся отдельно его средой: например, child Java в LANG=C может интерпретировать UTF8 argv
как ASCII. Передавайте LANG/LC_ALL=C.UTF-8, если это нужно инструменту; runtime не подставляет
locale скрыто. OutputEncoding управляет только чтением child stdout/stderr, не кодированием argv.

## Процесс, ожидание и остановка

| RU / EN | Контракт |
|---|---|
| `Запустить / Start()` | один запуск; повторный Start — явная ошибка |
| `ОжидатьЗавершения / WaitForExit(ms = -1)` | Boolean: true после exit, false при timeout **без kill** |
| `Завершить / Stop(ВсеДочерние = Ложь)` | остановка root; true также известных потомков |
| `Закрыть / Close()` | расширение juno, идемпотентно: kill/reap/streams/spools cleanup |
| `Завершен / HasExited` | readonly, root child завершён |
| `КодВозврата / ExitCode` | readonly, только после exit |
| `Идентификатор / ProcessId` | readonly PID после Start |
| `Имя / Name` | readonly basename заданного executable, доступен и после exit |
| `КодировкаВывода / OutputEncoding` | readonly исходный arg либо Неопределено при default UTF8 |
| `ПотокВывода / StdOut`, `ПотокОшибок / StdErr` | readonly TextOutputStream либо Неопределено до Start/без redirectOutput |
| `ПотокВвода / StdIn` | readonly TextInputStream; без redirectInput — ошибка |

Timeout — целые -1..2147483647 ms; -1 бесконечно, 0 poll. Он включает остаток host forwarding,
если output не перехватывается. Timeout не останавливает child: он может ещё работать либо уже завершиться при незавершённом
forwarding. Проверяйте HasExited; вызывайте Wait снова/Stop/Close.
RunApp(wait=true) ждёт полной передачи обоих outputs до размеров, зафиксированных после root exit,
с проверками отмены; произвольного усечения «через секунду» нет.
Ссылки/ожидания не удерживают общий resource monitor. Sleep/Приостановить(ms) принимает
неотрицательное целое, прерывается при cancellation.

OS разрешал Start снова после завершения; juno требует **новый объект**, чтобы не смешивать
старые cursors/resources с новым child. До Start HasExited/ExitCode/PID/Name — ошибка.
В OS Name мог ошибаться уже после exit, juno сохраняет имя.

## stdin и live outputs

Оба outputs с самого запуска пишутся в **разные private spool files**. Pipes stdout/stderr не могут
заблокироваться из-за непрочитанного второго потока. Spool растёт на диске; общего disk-size limit нет.
В памяти только chunks/одна текущая строка. Ввод и оба outputs могут быть больше heap одновременно.
RedirectOutput=false дополнительно пересылает оба файла в host stdout/stderr во время работы.
Не меняются и не закрываются System/host streams.

`ПотокВводаТекста/TextInputStream`: `Записать/Write(Текст)`, `ЗаписатьСтроку/WriteLine(Текст)`,
`Закрыть/Close()`. Текст UTF-8 без BOM, WriteLine добавляет native EOL. Как и в OS, optional
OutputEncoding не меняет stdin codec. Write немедленно flush. Для другого codec/точных binary bytes:

```bsl
ПолучитьДвоичныеДанныеИзСтроки("Я",КодировкаТекста.ANSI).Записать(П.StdIn);
ПолучитьДвоичныеДанныеИзHexСтроки("00FF").Записать(П.StdIn);
П.StdIn.Close(); // EOF child; требуется перед Wait, если child читает до EOF
```

Расширение juno `ЗаписатьБайты/WriteBytes(Буфер,Смещение,Количество)` пишет borrowed buffer chunks,
проверяет range/closed/cancel, ничего не сохраняет после возврата. Нулевой count тоже проверяет
состояние; output streams этого метода не имеют.

Input close идемпотентен; после него write ошибочен. Без redirectInput stdin child сразу закрывается
(EOF), **host stdin не наследуется**. Если child не читает, write/явный stdin.Close могут ждать;
Stop/owner cancellation сначала убивает child, затем закрывает pipe, освобождая blocked writer.

`ПотокВыводаТекста/TextOutputStream` (отдельный cursor для stdout и stderr):

| RU / EN | Контракт |
|---|---|
| `Прочитать / Read()` | nonblocking весь доступный текст, включая partial без LF; Нет данных → Неопределено |
| `ПрочитатьСтроку / ReadLine()` | nonblocking готовая строка без LF/CRLF/CR; partial сохраняется до разделителя/конца |
| `ЕстьДанные / HasData` | доступные bytes/текст, включая ещё неполный Unicode/line |
| `ПрочитатьБайты / ReadBytes(Буфер,Смещение,Количество)` | **juno extension**, raw chunks; число прочитанных bytes, 0 при отсутствии сейчас |
| `Закрыть / Close()` | juno extension: закрыть reader cursor; child продолжает писать spool |

Пустая готовая строка — `""`, отсутствие готового текста/EOF — Неопределено.
Поэтому Undefined во время работы не доказывает EOF: проверяйте Process.HasExited и оба outputs.
ReadLine не теряет partial line; Read отдаёт её. UTF8 sequence между child writes сохраняется до
полного символа; invalid/truncated sequence на настоящем конце даёт ошибку, без replacement.
Output codecs: UTF8, UTF16LE, windows1251/ANSI, IBM866/OEM (String либо TextEncoding enum).
BOM в process output сохраняется как текст, не определяет codec; это raw child stream, не file reader.

Read отвергает более16MiB доступных raw bytes; line/накопленный text buffer ограничены
16×1024×1024 UTF-16 code units. Большой binary/text вывод читайте ReadBytes и парсите chunks.
Raw и text делят byte cursor. Нельзя переключаться в raw с pending decoded text, incomplete sequence
или ожидающим LF после CR: явная ошибка, непрочитанные данные не выбрасываются. Не смешивайте эти режимы
в одном output; stdout и stderr независимы. Raw0 не означает EOF работающего child.

WaitForExit/HasExited относятся к root. Известный потомок может дописывать spool после root exit;
live reader остаётся доступен, пока потомок жив. ReadBytes после Wait копирует доступный в этот момент
snapshot; для полного дерева запущенный инструмент должен сам дождаться своих детей.

## Владение и пределы ОС

Process принадлежит ApplicationContext уже с Create, включая unstarted object. Явный Close или
scope success/error/cancel сначала останавливает child/известное дерево, затем pipes/cursors,
удаляет private files и завершает watcher. Root получает graceful destroy, затем force kill,
с bounded ожиданием/reap; ещё живому process не дают бесконечно удерживать shutdown.
Wait timeout сам этого cleanup не делает. RunApp(wait=false) тоже остаётся owned: чтобы child
дожил до конца, сохраните активный scope и дождитесь его; detached launch не поддержан.
Process/его text streams не передаются через ValueCopy — создавайте их внутри задания.

После завершения процесса и известных потомков временные файлы и дескрипторы
освобождаются автоматически: `Закрыть()` не требуется. Перенаправленный вывод до
16 МиБ на поток сохраняется в памяти и остаётся доступен для чтения. Больший вывод
остаётся в файле до явного Close или конца контекста; читайте его порциями.

Watch child tree через ProcessHandle.descendants во время жизни root; после root exit сохранённые
handles остаются для cleanup. Это **best-effort**, не OS containment: очень быстро отделившийся
потомок, reparenting или отказ ОС в enumeration могут скрыть handle. Остановка owned root выполняется независимо
от доступности enumeration; отказ ОС остановить root сообщается после bounded ожидания. Потомок, не являющийся прямым child JVM, reaped штатным init ОС;
Linux-контейнеру нужен init (`docker --init`), JVM PID1 не заменяет его.

Заимствованный host OutputStream может произвольно блокировать write/flush: juno не закрывает чужой
stream и не может принудительно завершить такой write. Finite Wait вернёт false; cleanup ждёт watcher
до секунды и сообщает ошибку, если host не отпускает поток. Для произвольного host sink используйте
redirectOutput=true и читайте spool самостоятельно. Жёсткий kill JVM/OS crash может оставить temp files;
постоянный supervisor/песочница не входят в этот API.

## Пример автоматизации

[Программа](../examples/процессы/Программа.bsl) подаёт файл в child stdin chunks по64KiB,
сохраняет отдельные binary stdout/stderr в **новые** файлы и возвращает child exit code.
Child outputs spool-ятся, пока передаётся stdin; ждать завершения до начала дренирования не требуется.

```sh
java -jar build/juno.jar run examples/процессы/Программа.bsl input.txt out.bin err.bin /usr/bin/sort
java -jar build/juno.jar build examples/процессы/Программа.bsl --output automate.jar
java -jar automate.jar input.txt out.bin err.bin /usr/bin/sort
```

На Windows задайте executable сортировщика/interpreter явно. Пример не заменяет существующие файлы;
частично созданные output-файлы при ошибке остаются для диагностики. Существующий source не обрезается.


Поиск Windows executable учитывает quoted PATH и PATHEXT для `.exe`/`.com`,
включая имена с точкой (`python3.12`). `.cmd`/`.bat` требуют явного `cmd.exe /c`.

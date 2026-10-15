# HTTP/HTTPS

Клиент работает на JDK 21 через `java.net.http` и JSSE без внешних зависимостей.
Сигнатуры сверены с OneScript 2.2.1.
Это проверенный поднабор, а не утверждение полной совместимости с платформой 1С.

## Соединение и запрос

`Новый HTTPСоединение` / `HTTPConnection`:

```bsl
Соединение = Новый HTTPСоединение(Сервер, Порт = 0, Пользователь = "", Пароль = "",
    Прокси = Неопределено, Таймаут = 0, ЗащищенноеСоединение = Неопределено,
    ИспользоватьАутентификациюОС = Ложь);
```

Сервер — hostname либо `http://host[:port]` / `https://host[:port]`, включая IPv6 в brackets.
Регистр схемы не важен. Userinfo, path кроме `/`, query и fragment в сервере отвергаются.
Порт 0 выбирает порт URI либо 80/443; конфликт явно заданных портов — ошибка.
TLS object с bare hostname выбирает HTTPS; явный http с TLS object отвергается.
Седьмой аргумент требует TLS object, **не Булево**. Аутентификация ОС не поддерживается.

Readonly свойства RU / EN: `Сервер/Host`, `Порт/Port`, `Пользователь/User`, `Пароль/Password`,
`Прокси/Proxy`, `Таймаут/Timeout`, `ИспользоватьАутентификациюОС/UseOSAuthentication`.
`РазрешитьАвтоматическоеПеренаправление/AllowAutoRedirect` — writable Булево, default true.

`Новый HTTPЗапрос` / `HTTPRequest(АдресРесурса = "", Заголовки = Неопределено)` имеет writable
`АдресРесурса/ResourceAddress` и `Заголовки/Headers`. Адрес относителен origin соединения:
`/v1?q=%D1%91`, `v1` или пустой. Уже percent-encoded фрагменты сохраняются; Unicode кодируется UTF-8.
Абсолютный URI, `//other-host` и fragment отвергаются до сети. URL-кодирование query values делайте отдельно.

| Метод соединения RU / EN | Аргументы |
|---|---|
| `Получить / Get` | `(Запрос, ФайлОтвета = Неопределено)` |
| `ОтправитьДляОбработки / Post` | `(Запрос, ФайлОтвета = Неопределено)` |
| `Записать / Put` | `(Запрос)` |
| `Удалить / Delete` | `(Запрос)` |
| `Изменить / Patch` | `(Запрос)` |
| `ПолучитьЗаголовки / Head` | `(Запрос)` |
| `ВызватьHTTPМетод / CallHTTPMethod` | `(Метод, Запрос, ФайлОтвета = Неопределено)` |

Custom method — HTTP token с буквальным регистром; CONNECT зарезервирован транспортом.
Для файла с PUT/PATCH используйте `CallHTTPMethod("PUT", запрос, путь)`.
GET/HEAD не отправляют тело запроса, но сохраняют явно заданные Content-Type/Content-Encoding.

| Методы запроса RU / EN | Аргументы / результат |
|---|---|
| `УстановитьТелоИзСтроки / SetBodyFromString` | `(Текст, Кодировка = "UTF-8", BOM = ByteOrderMarkUsage.Auto)` |
| `УстановитьТелоИзДвоичныхДанных / SetBodyFromBinaryData` | `(ДвоичныеДанные)`; snapshot байтов |
| `УстановитьИмяФайлаТела / SetBodyFileName` | `(Путь)`; существующий обычный файл |
| `ПолучитьТелоКакСтроку / GetBodyAsString` | `()` |
| `ПолучитьТелоКакДвоичныеДанные / GetBodyAsBinaryData` | `()` |
| `ПолучитьИмяФайлаТела / GetBodyFileName` | `()` → абсолютный путь либо Неопределено |

Unset body getters возвращают Неопределено; после пустой строки — `""` и Binary размером 0.
Строка по умолчанию UTF-8 **без BOM**; Content-Type автоматически не добавляется.
Поддержаны кодировки [файлового API](files.md), с ошибками на invalid bytes/непредставимые символы.
`ИспользованиеByteOrderMark/ByteOrderMarkUsage`: `Авто/Auto`, `НеИспользовать/DontUse` не добавляют BOM;
`Использовать/Use` явно отвергается (нужные bytes задайте BinaryData).
В OS Use фактически тоже не добавлял BOM; juno не принимает молча неработающий параметр.
GetBodyAsString после SetBodyFromString сохраняет выбранную кодировку; для binary/file использует
Content-Type charset либо UTF-8. В OneScript 2.2.1 ANSI setter затем читался как UTF-8 с заменами.
File body открывается при отправке: путь фиксируется при setter через cwd приложения,
содержимое берётся при send. Не меняйте источник до завершения запроса. OS мог открывать файл раньше.

## Заголовки и аутентификация

Headers запроса — изменяемое Соответствие без учёта регистра. Constructor/setter **копирует** исходный Map;
изменяйте `request.Headers`, а не исходный Map после constructor (OS сохранял исходную ссылку).
Case-only keys сливаются, последнее значение побеждает; обычные Соответствия языка остаются case-sensitive.
Значение — строка либо Массив/FixedArray строк, отправляемых отдельными полями.
Имена — ASCII HTTP tokens, значения — ASCII без CR/LF/control chars (TAB допустим).

Host, Content-Length, Connection, Expect, Upgrade, Transfer-Encoding, Proxy-Authorization и Proxy-Connection
управляются транспортом и явно отвергаются. Length вычисляется из тела; chunked encoding принимает JDK.
Нет cookie jar, ambient JVM proxy, Authenticator или глобального изменения TLS/System properties.

Пользователь/Пароль соединения задают preemptive Basic для его origin, если Authorization не задан явно.
Имя и пароль кодируются UTF-8 (сверено с 1С); username без `:`, управляющие символы запрещены. Basic поверх HTTP не шифруется; для токенов используйте HTTPS.
Bearer задаётся обычным `request.Headers.Insert("Authorization", "Bearer " + токен)`.

## Ответ, файлы и ресурсы

`HTTPОтвет/HTTPResponse` создаётся только соединением. Readonly `КодСостояния/StatusCode` и
`Заголовки/Headers`; 4xx/5xx возвращаются как ответы, а не как сетевые исключения.
Headers — FixedMap без учёта регистра: одно значение String, повторяющиеся — **FixedArray строк**,
включая Set-Cookie. Не объединяйте Set-Cookie через запятую. OS возвращал изменяемый Map и объединённые строки.

| Метод RU / EN | Контракт |
|---|---|
| `ПолучитьТелоКакСтроку / GetBodyAsString` | `(Кодировка = Неопределено)`; Content-Type charset либо UTF-8 |
| `ПолучитьТелоКакДвоичныеДанные / GetBodyAsBinaryData` | `()`; точные bytes |
| `ПолучитьИмяФайлаТела / GetBodyFileName` | `()`; переданное имя файла ответа либо Неопределено |
| `Закрыть / Close` | `()`; идемпотентно |

Пустой ответ, в том числе HEAD, даёт `""`/Binary размером 0 (OS GET мог давать Неопределено).
Charset допускает quoted имя. Неизвестная/неоднозначная кодировка и invalid bytes — ошибки text getter;
binary/file остаются доступны. Автоматической decompression нет: text getter отвергает Content-Encoding
кроме identity; binary/file сохраняют wire payload без transfer framing.

До возврата ответа тело по частям записывается в **private temporary spool**. Getters повторяемы и
не зависят от последующих изменений пользовательского файла. Whole getters ответа и file-backed запроса ограничены **16 MiB**;
большое тело сохраняйте через аргумент ФайлОтвета. SetBodyFromString/BinaryData и getters
уже находящегося в памяти request body такого лимита не добавляют; размер ограничен heap. File upload/download использует chunks 64 KiB,
без целого массива/строки. Spool требует места на диске; произвольный общий размер не ограничивается.

Файл ответа публикуется после полного получения через временный файл в каталоге назначения и replace move
(atomic, если поддерживает filesystem). При сетевом сбое существующий файл остаётся прежним.
Каталог должен существовать; пустой путь/каталоги/special files и совпадение с upload source, включая
hardlink/symlink aliases, отвергаются. Private spool удаляется сразу при получении ответа:
тело до 16 МиБ сохраняется в памяти, больший ответ доступен через указанный файл.
`Закрыть()` не требуется; необязательный Close сбрасывает тело из памяти, после него body getters ошибочны.

Все пути идут через виртуальный cwd `ApplicationContext`. Request/Connection принадлежат его lifetime;
exchange зарегистрирован как resource. Успех, исключение и отмена освобождают каналы,
future/subscription, threads и временные файлы. Ответ не удерживает файловые дескрипторы или private spool.
HTTP objects не передаются через ValueCopy в задание: создавайте их внутри задания.
Отмена закрывает owned handles вне общих мониторов и не закрывает host streams.

## Deadline и перенаправления

Timeout — целые секунды 0..86400. **0 — без ограничения**, как в 1С 8.3.27.1606 (там запрос с ответом
через 35 секунд и тело, приходящее 35 секунд, завершились успешно). Положительное значение — один общий
monotonic deadline на весь обмен: соединение, headers, получение всего тела, все redirects и публикацию
output file; в 1С при Timeout=5 запрос с постепенно приходящим телом тоже обрывается на пятой секунде. JDK HttpRequest.timeout сам по себе
не ограничивает stalled/trickled body после headers; здесь future ждёт полного spool с остатком deadline.
Сетевые/TLS/timeout ошибки сохраняют cause. Metadata filesystem calls и cleanup зависят от ОС;
остановка HttpClient ожидается не более секунды. Пользовательский код не получает частичный response.

Не более пяти redirects. Relative Location разрешён. HTTPS→HTTP запрещён.
На другом origin удаляются Authorization/Cookie и не добавляются credentials соединения.
POST/PUT и другое тело на другой origin не пересылаются. 303 превращает non-HEAD в GET и удаляет
Content-Type/Content-Encoding; 307/308 сохраняют метод/тело при том же origin.
301/302 для non-GET/HEAD требуют явной обработки (отключите auto redirect и проверьте response).
Автоматических application retries нет; повторная подписка JDK на тот же body publisher отвергается,
чтобы не повторить POST после неоднозначного сбоя. Compression, cache и connection pooling между вызовами
не реализованы; каждый exchange имеет отдельный client.

## Прокси и TLS

```bsl
Прокси = Новый ИнтернетПрокси(Ложь);
Прокси.Установить("http", "proxy.example", 8080, "", "", Ложь);
```

Это явный HTTP proxy: HTTP absolute-form и HTTPS CONNECT с обычной TLS-проверкой внутри tunnel.
Constructor true (system settings), proxy credentials/OS auth/SOCKS отвергаются.
Шестой аргумент Set имеет OS default true, поэтому укажите **Ложь** явно.
`НеИспользоватьПроксиДляЛокальныхАдресов/BypassProxyOnLocal` — writable false по умолчанию:
true пропускает localhost, 127.0.0.1, ::1 и hostname без точки.
`НеИспользоватьПроксиДляАдресов/BypassProxyOnAddresses` поддерживает только пустой массив.
Методы `Сервер/Server`, `Порт/Port`, `Пользователь/User`, `Пароль/Password` принимают `"http"`;
пока proxy не настроен, возвращают `""`, 0, `""`, `""`. Другие протоколы отвергаются.

По умолчанию HTTPS проверяет hostname и цепочку через **default JSSE trust**, TLS 1.2/1.3.
Ни один режим не отключает validation. Именованный OpenSSL facade не использует библиотеку OpenSSL.

```bsl
CA = Новый СертификатыУдостоверяющихЦентровФайл("corporate-ca.pem");
Клиент = Новый СертификатКлиентаФайл("client.p12", "пароль"); // optional для mTLS
TLS = Новый ЗащищенноеСоединениеOpenSSL(Клиент, CA);
Соединение = Новый HTTPСоединение("api.example", 443, "", "", Неопределено, 20, TLS);
```

EN: `FileCertificationAuthorityCertificates`, `FileClientCertificate`, `OpenSSLSecureConnection`.
TLS constructor 0..2 args `(ClientCertificate = Undefined, CAcertificates = Undefined)`.
CA constructor ровно один путь: PEM/DER X.509 bundle до 4 MiB, непустой, только действующие CA certificates.
Custom CA **заменяет** default trust. Client constructor `(Путь, Пароль = "")`:
PKCS12 `.p12/.pfx` до 16 MiB, ровно один PrivateKeyEntry с непустой certificate chain;
PEM private keys, OS stores, alias selection, callbacks, CRL/OCSP policy и расширенные параметры не поддержаны.
Проверка доверия сертификатам и имени сервера применяется также через CONNECT proxy.
Поддерживается mTLS с клиентским сертификатом из PKCS12.

Названия и назначение сверены с [официальным руководством администратора 1С 8.3.27](https://1c-dn.com/library/tutorials/1c_enterprise_administrator_guide_file_mode_8_3_27/),
[пояснением 1С о constructor SSL](https://1c-dn.com/forum/forum18/topic3948/) и
[официальными примечаниями о FileClientCertificate/CA](https://1c-dn.com/library/v8update_2756468690_mobile_platform/).
OS 2.2.1 не содержит SSL wrapper; runtime 1С здесь не запускался. Неподдержанные args/methods дают ошибку.

## URL и пример

`КодироватьСтроку/EncodeString(Строка, Способ, Кодировка = "UTF-8")` и
`РаскодироватьСтроку/DecodeString(...)` требуют enum `СпособКодированияСтроки/StringEncodingMethod`.
Поддержан `КодировкаURL/URLEncoding`: unreserved ASCII `A-Z a-z 0-9 -._~` сохраняются,
прочее percent bytes. Пробел `%20`, плюс `%2B`, raw `+` при decode остаётся `+`.
Это не application/x-www-form-urlencoded. Повторное EncodeString кодирует `%` как `%25`;
передача уже encoded ResourceAddress, напротив, `%` сохраняет.
UTF-8/1251/866/ASCII/Latin1; invalid percent/truncated escape/invalid UTF-8 — ошибка
(OS оставлял invalid percent без изменения). `URLВКодировкеURL/URLInURLEncoding` сохраняет разделители целого URL и готовые percent escapes.

[REST-пример](../examples/http/Программа.bsl) отправляет точный JSON, берёт Bearer из среды,
сохраняет ответ в новый файл, обрабатывает HTTP error и пишет Unicode diagnostics в stderr:

```sh
JUNO_HTTP_TOKEN=demo java -jar build/juno.jar run examples/http/Программа.bsl localhost 8080 /api response.json
java -jar build/juno.jar build examples/http/Программа.bsl --output rest.jar
JUNO_HTTP_TOKEN=demo java -jar rest.jar https://api.example 443 /v1/items response.json
```

Пример требует endpoint, принимающий POST; команды с api.example иллюстрируют параметры и не выполняются тестами.
Для большой выгрузки замените SetBodyFromString на SetBodyFileName, для скачивания — Get(запрос, путь).


`СпособКодированияСтроки.URLВКодировкеURL` сохраняет разделители целого URL и
существующие percent escapes, кодируя не-ASCII символы выбранной кодировкой.


JDK-клиент принимает 204 с Content-Length: 0, но отвергает 204 с ненулевым
Content-Length или Transfer-Encoding: такой ответ объявляет запрещённое для
204 тело. Это осознанно более строгий контракт, чем терпимость платформы к
некорректному серверу; ошибка juno сообщает причину по-русски.

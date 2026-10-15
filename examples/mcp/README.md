# Простой MCP-сервер

[Программа.bsl](Программа.bsl) — сервер с двумя инструментами на чистом BSL:

- `greet(name)` возвращает приветствие.
- `sum(a, b)` складывает числа с точной десятичной арифметикой: `0.1 + 0.2 = 0.3`.

Используется MCP `2025-11-25` через stdio: клиент запускает сервер как дочерний процесс,
пишет JSON-RPC в stdin и читает ответы из stdout. Одно сообщение занимает одну строку UTF-8.
`initialize` согласует версию и возможности, затем клиент отправляет `notifications/initialized`.
Поддержаны также `ping`, `tools/list`, `tools/call`, ошибки JSON-RPC и ошибки аргументов инструментов.
Уведомления не получают ответов. EOF завершает сервер.

Это учебный поднабор: только инструменты и stdio, без resources, prompts и HTTP.
Клиент должен поддерживать handshake `initialize` и версию `2025-11-25`;
`server/discover` и протокол `2026-07-28` здесь не реализованы.

## Запуск

Из корня репозитория, после `java Build.java`:

```sh
java -jar build/juno.jar run examples/mcp/Программа.bsl
```

Готовый диалог — в [requests.jsonl](requests.jsonl). Чтобы увидеть четыре ответа:

```sh
java -jar build/juno.jar run examples/mcp/Программа.bsl < examples/mcp/requests.jsonl
```

В ответах будут возможности сервера, список инструментов, `Привет, мир!` и `0.3`.
На `notifications/initialized` сервер ничего не печатает.

## Самостоятельный JAR и MCP-клиент

```sh
java -jar build/juno.jar build examples/mcp/Программа.bsl --output build/mcp-server.jar
java -jar build/mcp-server.jar < examples/mcp/requests.jsonl
```

JAR работает без исходников и установки juno. В клиенте с форматом настроек `mcpServers`
укажи абсолютный путь к собранному JAR:

```json
{
  "mcpServers": {
    "juno-example": {
      "command": "java",
      "args": ["-jar", "/путь/к/mcp-server.jar"]
    }
  }
}
```

Нужна Java 21+. Подставь свой путь; клиент сам запустит и остановит процесс.
В stdout должны попадать только ответы протокола: не добавляй `Сообщить` для отладки.
Необработанные ошибки juno выводит в stderr.

Контракт: [транспорт stdio](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#stdio),
[инициализация](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle),
[инструменты](https://modelcontextprotocol.io/specification/2025-11-25/server/tools).

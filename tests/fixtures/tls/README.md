# Сертификаты для HTTP-тестов

Локальные тестовые сертификаты для `HttpLibraryTests`: CA, сервер с SAN `localhost`,
клиент для mTLS и самоподписанный сервер. Пароль PKCS12: `changeit`.
Ключи публичные и предназначены только для тестов.

Чтобы обновить сертификаты, из корня репозитория выполни:

```sh
python3 tests/fixtures/make-certificates.py --keytool /путь/к/jdk/bin/keytool
```

Генератор использует только Python 3 и `keytool` из JDK. Он создаёт новые ключи,
сертификаты со сроком действия 3650 дней и `provenance.json` с версией JDK и SHA-256.

# HappyFilterCaptcha — документация по использованию

Капча-сервер: сундук → шалкер → кирка → воронка. Только Bukkit API, Spigot/Paper/Purpur 1.14–1.21.

## 1. Как это работает

1. Игрок заходит → плагин телепортирует в мир `captcha`, чистит инвентарь, выдаёт 1 кирку с меткой сессии.
2. Сообщения: `Пройди капчу → Переложи еду → Сломай киркой → Брось в воронку` + title + actionbar 30 сек.
3. Сундук генерируется каждый раз: 3 вида еды по 1–4 шт + мусор 8–12 шт в случайных слотах. Ожидаемый набор запоминается.
4. Шалкер ломается только киркой в главной руке. Ванильный дроп отменяется, создаётся предмет с содержимым через `BlockStateMeta` + метка `PersistentDataContainer`.
5. Проверка в момент `InventoryPickupItemEvent` воронки: метка → сверка 1в1 по типам и количеству. Лишнее = `EXTRA_ITEM`, меньше = `NOT_ALL_FOOD`.
6. Успех: чистка воронки/дропов, возврат сохранённого инвентаря, `Вы успешно прошли капчу`, дальше по `mode`.
7. Любая ошибка = кик (попыток 1). Выход с сервера во время капчи = без кика, в следующий раз заново.
8. Комната одна: пока один в капче, остальные в очереди заморожены.

Запрещено до конца капчи: ломать чужое, ставить блоки, открывать чужое (воронку нельзя), дропать не-шалкер, чат и команды (кроме `/happycaptcha` с `happyfilter.admin`), есть/крафтить/вёдра, телепорты/выход за границы платформы.

## 2. Что нужно

- Java 17, Purpur/Paper 1.20.1.
- 3 процесса: captcha `25565`, lobby `25566`, Velocity `25564`.
- Везде `online-mode=false` (тест/пиратка, авторизация через LimboAuth).
- Velocity `player-info-forwarding-mode = "modern"`, один `forwarding.secret` на всех.
- Мир `World` с платформой: сундук `175,174,-181`, воронка `173,173,-181` (над ней воздух), шалкер `171,174,-181`, спавн `173.5,174,-185.5`, границы `167,173,-187 → 179,177,-176`.

## 3. Установка

1. Собери: `mvn package -DskipTests` → `target/HappyFilterCaptcha-1.0.0.jar`.
2. Положи jar в `Captcha/plugins/`, запусти и останови сервер — появится `plugins/HappyFilterCaptcha/`.
3. Скопируй папку `World` (с `level.dat`, `region/`) в `plugins/HappyFilterCaptcha/World/`.
4. Проверь `plugins/HappyFilterCaptcha/config.yml`: `captcha.world: captcha`, координаты как выше, `mode: PROXY`, `proxy.server: lobby`.
5. Папку `./captcha/` в корне не создавай — плагин скопирует сам. Существующий мир не перезаписывает.
6. Запусти: lobby → captcha (`Captcha world copied and loaded`) → velocity. Вход только на `:25564`.

## 4. Конфиги

`velocity.toml`: `bind 25564`, `captcha=127.0.0.1:25565`, `lobby=127.0.0.1:25566`, `try=["captcha"]`, `bungee-plugin-message-channel=true`.

Оба `paper-global.yml`: `proxies.velocity.enabled=true`, `online-mode` такой же как в velocity (`false` для пиратки), `secret` 1в1 из `velocity/forwarding.secret`.

`server.properties` обоих бэкендов: `online-mode=false`, свои порты.

## 5. Команды и права

- `/happycaptcha reload` — перезалить конфиги.
- `/happycaptcha test [ник]` — принудительный старт (комната должна быть свободна).
- `/happycaptcha status [ник]` — active/waiting.
- `happyfilter.bypass (default op)` — обход, таких не тепает.
- `happyfilter.admin (default op)` — команды.

## 6. Логи

- Кики — консоль капчи: `HappyFilterCaptcha: kicked <ник> reason <КОД>`.
- `Captcha room prepared: expected={...} nonEmpty=N` — генерация ок.
- Внутренние ошибки — `plugins/HappyFilterCaptcha/errors.log`. Пустой = ок.

## 7. Ошибки и лечение

- Сундук пуст вне капчи — норма, `resetRoom` чистит. Смотри только при `active`.
- OP не тепает — это `bypass`, тестируй с `/deop` + перезахід.
- `Address already in use` — два сервера на одном порту. Должно быть 25564/25565/25566.
- `credentials can not be null` — заход напрямую на 25565/25566 при `modern`. Заходи только на 25564.
- `[initial connection] disconnected` при `online-mode=true` с пиратским клиентом — ставь `false` везде.
- `TIMEOUT` — не успел за 30 сек, увеличь `timeout-seconds` при лагах.
- `WRONG_INVENTORY` — открыл воронку/чужое. `OUT_OF_BOUNDS` — вышел за `bounds`.

![banner](https://cdn.modrinth.com/data/cached_images/5c23f126437ca1051c02a2f0a6be31de4ad1999f_0.webp)
<details>
<summary>English verion</summary>

# HappyFilterCaptcha ⛏️

EXAMPLE: https://github.com/reger-dev/HappyFilter-captcha/tree/main/example%20use%20on%20server%20with%20velocity

Antibot captcha with real gameplay: **chest → food → shulker → pickaxe → hopper**. Bots fail, real players pass in ~20 seconds.

## How it works
1. Join → teleport to void captcha world, inventory cleared, session-tagged pickaxe given
2. Chest is generated every time: **food (3 types x 1–4) + junk (8–12)** in random slots
3. Move **all food** into the shulker, break it **with the pickaxe in main hand**, drop it into the hopper
4. Check on `InventoryPickupItemEvent`: `PersistentDataContainer` tag + exact **1:1 type+count** match
5. Success → `Connect lobby` via `BungeeCord` channel. Any fail → kick. Quit mid-captcha = no kick, fresh retry. Single room, others wait frozen in queue.

## Features
- **Bukkit API only**, no NMS — **Spigot / Paper / Purpur 1.14–1.21**
- Behind **BungeeCord and Velocity** (+ ViaVersion on proxy)
- Self-copying world: `plugins/HappyFilterCaptcha/World → ./captcha`, never overwrites yours
- `PEACEFUL`, `doMobSpawning false`, chunks always loaded
- All texts in `messages.yml` with `&` colors, title + 30s actionbar timer
- Kicks → console, exceptions → `errors.log`
- `/happycaptcha reload | test | status`, `happyfilter.bypass`, `happyfilter.admin`

## Requirements
- `captcha` + `lobby` backends + proxy, `mode: PROXY`
- Chest/hopper/shulker coords from your `World` in `config.yml`

📖 **Full docs on english:** 


```
https://docs.google.com/document/d/e/2PACX-1vQjmJ25o2dh-ISDPF4o-MHkvQzyArNpnJEmWrKzz_1NcYac1tXLH4r0eG2gFUFji2cy2xrx4bNQAr9I/pub
```


EXAMPLE: https://github.com/reger-dev/HappyFilter-captcha/tree/main/example%20use%20on%20server%20with%20velocity

This is a BETA version. You MUST download the captcha world itself.


```
https://drive.google.com/drive/folders/1RFrbC6dnvoFC88PK8WNb7hf-iwqB3eSS?usp=drive_link
```


Captcha world: plugins\HappyFilterCaptcha\

</details>



<details>
<summary>Русская версия</summary>

# HappyFilterCaptcha ⛏️


ПРИМЕР: https://github.com/reger-dev/HappyFilter-captcha/tree/main/example%20use%20on%20server%20with%20velocity

Анти-бот капча с настоящей игровой механикой: **сундук → еда → шалкер → кирка → воронка**. Боты отваливаются, живые игроки проходят за 20 секунд.

## Как это работает
1. Игрок заходит → телепорт в void-мир капчи, инвентарь чистится, выдаётся кирка с меткой сессии
2. В сундуке генерируется **еда (3 вида по 1–4) + мусор (8–12)** в случайных слотах
3. Надо переложить **всю еду** в шалкер, сломать его **киркой в главной руке** и бросить в воронку
4. Проверка в момент засасывания воронкой: метка `PersistentDataContainer` + сверка **1в1 по типам и количеству**
5. Успех → `Connect lobby` через `BungeeCord` канал. Любая ошибка → кик. Выход во время капчи — без кика, заново. Комната одна, остальные ждут в очереди.

## Фишки
- Только **Bukkit API**, без NMS — **Spigot / Paper / Purpur 1.14–1.21**
- Работает за **BungeeCord и Velocity** (+ ViaVersion на прокси)
- Мир копируется сам: `plugins/HappyFilterCaptcha/World → ./captcha`, чужой мир не трогает
- `PEACEFUL`, `doMobSpawning false`, чанки всегда загружены
- Все тексты в `messages.yml` с `&`-цветами, title + actionbar-таймер 30 сек
- Кики — в консоль, исключения — в `errors.log`
- `/happycaptcha reload | test | status`, права `happyfilter.bypass`, `happyfilter.admin`

## Нужно
- Backend `captcha` + `lobby` + прокси, `mode: PROXY`
- Координаты сундука/воронки/шалкера из вашего `World` в `config.yml`

📖 **Полная документация на русском:** 


```
https://docs.google.com/document/d/e/2PACX-1vRWkVIsMp2eqTcFBY8u6HADpD9wwxe36xcpO7X6nsVj9Sq_S7s6uJiha3FdGrCnKfej661MfSaph9L0/pub
```


Эта БЕТА версия, к ней ОБЕЗАТЕЛЬНО скачивайте мир самой капчи


```
https://drive.google.com/drive/folders/1RFrbC6dnvoFC88PK8WNb7hf-iwqB3eSS?usp=drive_link
```


Мир капчи кидать суда \plugins\HappyFilterCaptcha


ПРИМЕР: https://github.com/reger-dev/HappyFilter-captcha/tree/main/example%20use%20on%20server%20with%20velocity

</details>



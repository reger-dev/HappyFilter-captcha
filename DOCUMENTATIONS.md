# HappyFilterCaptcha — Usage Documentation

Captcha server: chest → shulker → pickaxe → hopper. Bukkit API only, Spigot/Paper/Purpur 1.14–1.21.

## 1. How it works

1. Player joins → teleport to `captcha` world, inventory cleared, 1 session-tagged pickaxe given.
2. Chat: `Pass the captcha → Move all food → Break with pickaxe → Drop into hopper` + title + 30s actionbar timer.
3. Chest is generated every time: 3 food types x 1–4 + junk 8–12 in random slots. Expected set is stored.
4. Shulker breaks only with the pickaxe in main hand — vanilla drop cancelled, tagged item with contents created via `BlockStateMeta` + `PersistentDataContainer` mark.
5. Check on hopper `InventoryPickupItemEvent`: tag → exact 1:1 type+count match. Extra = `EXTRA_ITEM`, missing = `NOT_ALL_FOOD`.
6. Success: hopper/drops cleanup, saved inventory restored, `You passed` message, then by `mode`.
7. Any fail = kick (1 attempt). Quit mid-captcha = no kick, fresh retry next join. Single room, others wait frozen in queue.

Blocked until done: breaking/placing (except shulker), opening foreign inventories (hopper forbidden), dropping non-shulker, chat/commands (except `/happycaptcha` with `happyfilter.admin`), eating/crafting/buckets, teleports/leaving platform bounds.

## 2. Requirements

- Java 17, Purpur/Paper 1.20.1.
- 3 processes: captcha `25565`, lobby `25566`, Velocity `25564`.
- `online-mode=false` everywhere (test/cracked, auth via LimboAuth).
- Velocity `player-info-forwarding-mode = "modern"`, single `forwarding.secret` everywhere.
- `World` with platform: chest `175,174,-181`, hopper `173,173,-181` (air above), shulker `171,174,-181`, spawn `173.5,174,-185.5`, bounds `167,173,-187 → 179,177,-176`.

## 3. Install

1. Build: `mvn package -DskipTests` → `target/HappyFilterCaptcha-1.0.0.jar`.
2. Drop jar into `Captcha/plugins/`, start/stop once — `plugins/HappyFilterCaptcha/` appears.
3. Copy your `World` folder (with `level.dat`, `region/`) into `plugins/HappyFilterCaptcha/World/`.
4. Check `plugins/HappyFilterCaptcha/config.yml`: `captcha.world: captcha`, coords as above, `mode: PROXY`, `proxy.server: lobby`.
5. Do NOT create `./captcha/` manually — plugin copies it. Existing worlds are never overwritten.
6. Start order: lobby → captcha (`Captcha world copied and loaded`) → velocity. Join only via `:25564`.

## 4. Configs

`velocity.toml`: `bind 25564`, `captcha=127.0.0.1:25565`, `lobby=127.0.0.1:25566`, `try=["captcha"]`, `bungee-plugin-message-channel=true`.

Both `paper-global.yml`: `proxies.velocity.enabled=true`, `online-mode` same as velocity (`false` for cracked), `secret` exactly from `velocity/forwarding.secret`.

Both backends `server.properties`: `online-mode=false`, own ports.

## 5. Commands & permissions

- `/happycaptcha reload` — reload configs.
- `/happycaptcha test [nick]` — force start (room must be free).
- `/happycaptcha status [nick]` — active/waiting.
- `happyfilter.bypass (default op)` — skip, never teleported.
- `happyfilter.admin (default op)` — commands.

## 6. Logs

- Kicks — captcha console: `HappyFilterCaptcha: kicked <nick> reason <CODE>`.
- `Captcha room prepared: expected={...} nonEmpty=N` — generation OK.
- Internal errors — `plugins/HappyFilterCaptcha/errors.log`. Empty = OK.

## 7. Troubleshooting

- Empty chest outside captcha — normal, `resetRoom` clears. Check only while `active`.
- OP not teleported — that's `bypass`, test with `/deop` + rejoin.
- `Address already in use` — two servers on same port. Must be 25564/25565/25566.
- `credentials can not be null` — direct join to 25565/25566 with `modern`. Join only 25564.
- `[initial connection] disconnected` with cracked client — needs `online-mode=false` everywhere.
- `TIMEOUT` — didn't finish in 30s, raise `timeout-seconds` on lag.
- `WRONG_INVENTORY` — opened hopper/foreign. `OUT_OF_BOUNDS` — left `bounds`.

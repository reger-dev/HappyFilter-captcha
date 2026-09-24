package ru.happyfilter.captcha.session;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Hopper;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import ru.happyfilter.captcha.HappyFilterCaptcha;
import ru.happyfilter.captcha.config.ConfigManager;
import ru.happyfilter.captcha.messages.MessageManager;
import ru.happyfilter.captcha.model.CaptchaSession;
import ru.happyfilter.captcha.proxy.ProxyService;
import ru.happyfilter.captcha.util.ErrorLogger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Менеджер сессий: очередь, подготовка комнаты, генерация, таймер, успех/кик.
 * Комната одна - активный игрок один, остальные ждут.
 */
public class SessionManager {

    private final HappyFilterCaptcha plugin;
    private final ConfigManager config;
    private final MessageManager messages;
    private final ProxyService proxy;
    private final ErrorLogger errors;

    private World captchaWorld;

    private final Map<UUID, CaptchaSession> activeMap = new HashMap<UUID, CaptchaSession>();
    // Активный игрок (только один). null если комната свободна.
    private UUID activePlayer = null;
    // Очередь ожидания (UUID по порядку входа)
    private final Deque<UUID> queue = new ArrayDeque<UUID>();
    // Телепорты, инициированные плагином (чтобы не ловить OUT_OF_BOUNDS / отмену)
    private final Set<UUID> teleportBypass = new HashSet<UUID>();

    private final Random random = new Random();

    private NamespacedKey sessionKey;

    public SessionManager(HappyFilterCaptcha plugin, ConfigManager config,
                          MessageManager messages, ProxyService proxy, ErrorLogger errors) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.proxy = proxy;
        this.errors = errors;
    }

    public void init(World world) {
        this.captchaWorld = world;
        this.sessionKey = new NamespacedKey((JavaPlugin) plugin, "captcha_session");
    }

    public World getCaptchaWorld() { return captchaWorld; }
    public NamespacedKey getSessionKey() { return sessionKey; }

    public boolean isCaptchaPlayer(UUID id) {
        return (activePlayer != null && activePlayer.equals(id)) || queue.contains(id);
    }

    public boolean isActive(UUID id) {
        return activePlayer != null && activePlayer.equals(id);
    }

    public boolean isWaiting(UUID id) {
        return queue.contains(id);
    }

    public CaptchaSession getSession(UUID id) {
        return activeMap.get(id);
    }

    public UUID getActivePlayer() { return activePlayer; }
    public int getQueueSize() { return queue.size(); }

    public int queuePosition(UUID id) {
        int i = 1;
        for (UUID u : queue) {
            if (u.equals(id)) return i;
            i++;
        }
        return -1;
    }

    public boolean allowTeleport(UUID id) {
        return teleportBypass.contains(id);
    }

    public void addTeleportBypass(UUID id) {
        teleportBypass.add(id);
    }

    public void removeTeleportBypass(UUID id) {
        teleportBypass.remove(id);
    }

    // ==================== JOIN / QUEUE ====================

    /** Вызывается при входе игрока (уже проверен bypass). */
    public void onJoin(final Player player) {
        final UUID id = player.getUniqueId();
        if (isCaptchaPlayer(id)) return;

        if (activePlayer == null) {
            // Комната свободна - старт сразу (с задержкой 1 тик чтобы игрок прогрузился)
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override
                public void run() {
                    if (!player.isOnline()) return;
                    if (activePlayer != null) {
                        enqueueWaiting(player);
                    } else {
                        startSession(player);
                    }
                }
            }, 1L);
        } else {
            enqueueWaiting(player);
            // Ожидающих тоже держим в мире капчи замороженными
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override
                public void run() {
                    if (!player.isOnline()) return;
                    teleportToCaptcha(player);
                    int pos = queuePosition(id);
                    player.sendMessage(messages.format("queue-wait", new String[][]{{"{pos}", String.valueOf(pos)}}));
                }
            }, 1L);
        }
    }

    private void enqueueWaiting(Player player) {
        if (!queue.contains(player.getUniqueId())) {
            queue.addLast(player.getUniqueId());
        }
    }

    /** Принудительный старт для /happycaptcha test */
    public void forceStart(Player player) {
        UUID id = player.getUniqueId();
        queue.remove(id);
        CaptchaSession old = activeMap.get(id);
        if (old != null) {
            cleanupSession(id, false);
        }
        if (activePlayer != null && !activePlayer.equals(id)) {
            // Активного кикаем? Нет - ставим тестера в начало очереди.
            // Проще: если комната занята другим - кидаем ошибку в команду (проверяется в команде).
            // Здесь только прямой старт когда свободно.
        }
        startSession(player);
    }

    // ==================== START ====================

    private void startSession(final Player player) {
        final UUID id = player.getUniqueId();
        try {
            if (captchaWorld == null) {
                errors.log("startSession: captchaWorld is null", null);
                return;
            }
            queue.remove(id);

            CaptchaSession session = new CaptchaSession(id);
            activeMap.put(id, session);
            activePlayer = id;

            savePlayerState(player, session);

            // Телепорт в капчу
            teleportToCaptcha(player);

            // Подготовка комнаты и генерация (после телепорта, на след. тике чтобы чанки были)
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!player.isOnline()) {
                            handleQuit(player.getUniqueId());
                            return;
                        }
                        prepareRoom(session);
                        givePickaxe(player, session);

                        // Сообщения шагов
                        player.sendMessage(messages.get("step1"));
                        player.sendMessage(messages.get("step2"));
                        player.sendMessage(messages.get("step3"));
                        player.sendMessage(messages.get("step4"));

                        // Title
                        try {
                            player.sendTitle(
                                    messages.get("title-main"),
                                    messages.get("title-sub"),
                                    10, 60, 20);
                        } catch (Exception e) {
                            errors.log("sendTitle failed", e);
                        }

                        startCountdown(player, session);
                    } catch (Exception e) {
                        errors.log("prepareRoom failed for " + player.getName(), e);
                        fail(player, "TIMEOUT");
                    }
                }
            }, 2L);

        } catch (Exception e) {
            errors.log("startSession failed for " + player.getName(), e);
        }
    }

    private void teleportToCaptcha(Player player) {
        try {
            Location spawn = config.getCaptchaSpawn(captchaWorld);
            // Гарантируем чанк загружен
            spawn.getChunk().load(true);
            teleportBypass.add(player.getUniqueId());
            player.teleport(spawn);
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override
                public void run() {}
            }, 2L);
            // Снимаем bypass через 5 тиков (телепорт уже произошёл)
            final UUID id = player.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override
                public void run() {
                    teleportBypass.remove(id);
                }
            }, 5L);
            player.setGameMode(GameMode.SURVIVAL);
            player.setFlying(false);
            player.setAllowFlight(false);
        } catch (Exception e) {
            errors.log("teleportToCaptcha failed", e);
        }
    }

    private void savePlayerState(Player player, CaptchaSession session) {
        try {
            session.setSavedContents(cloneArray(player.getInventory().getContents()));
            session.setSavedArmor(cloneArray(player.getInventory().getArmorContents()));
            try {
                session.setSavedOffHand(player.getInventory().getItemInOffHand() == null ? null
                        : player.getInventory().getItemInOffHand().clone());
            } catch (Throwable t) {
                session.setSavedOffHand(null);
            }
            session.setSavedLevel(player.getLevel());
            session.setSavedExp(player.getExp());
            session.setSavedGameMode(player.getGameMode());
            session.setSavedFood(player.getFoodLevel());
            session.setSavedHealth(player.getHealth());
        } catch (Exception e) {
            errors.log("savePlayerState failed", e);
        }
    }

    private ItemStack[] cloneArray(ItemStack[] arr) {
        if (arr == null) return new ItemStack[0];
        ItemStack[] out = new ItemStack[arr.length];
        for (int i = 0; i < arr.length; i++) {
            out[i] = arr[i] == null ? null : arr[i].clone();
        }
        return out;
    }

    private void restorePlayerState(Player player, CaptchaSession session) {
        try {
            player.getInventory().clear();
            if (session.getSavedContents() != null) {
                player.getInventory().setContents(session.getSavedContents());
            }
            if (session.getSavedArmor() != null) {
                player.getInventory().setArmorContents(session.getSavedArmor());
            }
            try {
                if (session.getSavedOffHand() != null) {
                    player.getInventory().setItemInOffHand(session.getSavedOffHand());
                }
            } catch (Throwable ignored) {}
            try { player.setLevel(session.getSavedLevel()); } catch (Exception ignored) {}
            try { player.setExp(session.getSavedExp()); } catch (Exception ignored) {}
            try {
                if (session.getSavedGameMode() != null) player.setGameMode(session.getSavedGameMode());
            } catch (Exception ignored) {}
        } catch (Exception e) {
            errors.log("restorePlayerState failed", e);
        }
    }

    // ==================== ROOM ====================

    /** Очистка + генерация сундука/шалкера/воронки под сессию. */
    private void prepareRoom(CaptchaSession session) {
        // Принудительно грузим чанки всех трёх блоков, иначе getState/setType на выгруженном чанке молча не применяется
        try {
            config.getShulkerLocation(captchaWorld).getChunk().load(true);
            config.getHopperLocation(captchaWorld).getChunk().load(true);
            config.getChestLocation(captchaWorld).getChunk().load(true);
        } catch (Exception e) {
            errors.log("prepareRoom: chunk load failed", e);
        }
        // Шалкер-блок: поставить заново и очистить
        Location shLoc = config.getShulkerLocation(captchaWorld);
        Material shMat = config.getShulkerMaterial();
        Block shBlock = shLoc.getBlock();
        shBlock.setType(shMat, false);
        try {
            if (shBlock.getState() instanceof ShulkerBox) {
                ShulkerBox box = (ShulkerBox) shBlock.getState();
                box.getInventory().clear();
                box.update(true, false);
            } else {
                errors.log("prepareRoom: shulker block is not ShulkerBox after setType, got " + shBlock.getType(), null);
            }
        } catch (Exception e) {
            errors.log("prepareRoom: shulker clear failed", e);
        }

        // Воронка: очистить
        Location hLoc = config.getHopperLocation(captchaWorld);
        try {
            Block hBlock = hLoc.getBlock();
            if (hBlock.getState() instanceof Hopper) {
                Hopper h = (Hopper) hBlock.getState();
                h.getInventory().clear();
                h.update(true, false);
            } else {
                // Если воронка разрушена/заменена - восстановить
                hBlock.setType(Material.HOPPER, false);
                if (hBlock.getState() instanceof Hopper) {
                    ((Hopper) hBlock.getState()).getInventory().clear();
                }
            }
            // Над воронкой должен быть воздух
            Block above = hLoc.clone().add(0, 1, 0).getBlock();
            if (!above.getType().isAir()) {
                above.setType(Material.AIR, false);
            }
        } catch (Exception e) {
            errors.log("prepareRoom: hopper clear failed", e);
        }

        // Убрать валяющиеся предметы в границах (от прошлых сессий)
        try {
            for (Entity en : captchaWorld.getEntitiesByClass(Item.class)) {
                Location l = en.getLocation();
                if (config.isInsideBounds(l)) {
                    en.remove();
                }
            }
        } catch (Exception e) {
            errors.log("prepareRoom: clear drops failed", e);
        }

        // Сундук: очистить и сгенерировать еду + мусор
        // Важно: Block#getState() возвращает snapshot-копию. Правильный порядок:
        // взять свежий state, править его инвентарь, затем update(true,true) чтобы пушнуть в мир.
        Location cLoc = config.getChestLocation(captchaWorld);
        try { cLoc.getChunk().load(true); } catch (Exception ignored) {}
        try {
            Block live = cLoc.getBlock();
            if (!(live.getState() instanceof Chest)) {
                live.setType(Material.CHEST, false);
            }
        } catch (Exception e) {
            errors.log("prepareRoom: chest setType failed at " + cLoc, e);
        }
        try {
            Block live2 = cLoc.getBlock();
            if (!(live2.getState() instanceof Chest)) {
                errors.log("prepareRoom: block at chest loc is not Chest, got " + live2.getType() + " at " + cLoc, null);
                return;
            }
            Chest chest = (Chest) live2.getState();
            chest.getSnapshotInventory().clear();
            Map<Material, Integer> expected = generateContents(chest.getSnapshotInventory());
            session.getExpectedFood().clear();
            session.getExpectedFood().putAll(expected);
            chest.update(true, true);
            // Проверочное чтение живого блока после update
            try {
                Chest verify = (Chest) cLoc.getBlock().getState();
                int nonEmpty = 0;
                for (ItemStack it : verify.getSnapshotInventory().getContents()) {
                    if (it != null && it.getType() != Material.AIR) nonEmpty++;
                }
                plugin.getLogger().info("Captcha room prepared: expected=" + expected + " chest=" + cLoc + " nonEmpty=" + nonEmpty);
                if (nonEmpty == 0) {
                    errors.log("prepareRoom: chest verify EMPTY after fill at " + cLoc + " expected=" + expected, null);
                }
            } catch (Throwable t) {
                errors.log("prepareRoom: chest verify failed", t);
            }
        } catch (Exception e) {
            errors.log("prepareRoom: chest fill failed", e);
        }
    }

    /**
     * Генерация: случайно 3 вида еды по 1-4 шт + 8-12 мусора, по случайным слотам.
     * Возвращает ожидаемый набор еды (тип -> кол-во).
     */
    private Map<Material, Integer> generateContents(Inventory chestInv) {
        List<Material> foodPool = config.getFoodPool();
        List<Material> trashPool = config.getTrashPool();
        int foodTypes = Math.min(config.getFoodTypes(), foodPool.size());
        int foodMin = config.getFoodMin();
        int foodMax = config.getFoodMax();
        int trashMin = config.getTrashMin();
        int trashMax = config.getTrashMax();

        Collections.shuffle(foodPool, random);
        Collections.shuffle(trashPool, random);

        Map<Material, Integer> expected = new HashMap<Material, Integer>();
        List<ItemStack> toPlace = new ArrayList<ItemStack>();

        for (int i = 0; i < foodTypes; i++) {
            Material m = foodPool.get(i);
            int amount = foodMin + (foodMax > foodMin ? random.nextInt(foodMax - foodMin + 1) : 0);
            if (amount < 1) amount = 1;
            expected.put(m, amount);
            toPlace.add(new ItemStack(m, amount));
        }

        int trashCount = trashMin + (trashMax > trashMin ? random.nextInt(trashMax - trashMin + 1) : 0);
        for (int i = 0; i < trashCount; i++) {
            Material m = trashPool.get(random.nextInt(trashPool.size()));
            int amount = 1 + random.nextInt(3); // 1-3 шт мусора в стаке для естественности
            toPlace.add(new ItemStack(m, amount));
        }

        // Случайные слоты
        Collections.shuffle(toPlace, random);
        int size = chestInv.getSize();
        List<Integer> slots = new ArrayList<Integer>();
        for (int i = 0; i < size; i++) slots.add(i);
        Collections.shuffle(slots, random);
        for (int i = 0; i < toPlace.size() && i < slots.size(); i++) {
            chestInv.setItem(slots.get(i), toPlace.get(i));
        }
        return expected;
    }

    private void givePickaxe(Player player, CaptchaSession session) {
        try {
            player.getInventory().clear();
            try {
                player.getInventory().setArmorContents(new ItemStack[player.getInventory().getArmorContents().length]);
            } catch (Exception ignored) {}
            try { player.getInventory().setItemInOffHand(null); } catch (Throwable ignored) {}

            Material mat = config.getPickaxeMaterial();
            ItemStack pick = new ItemStack(mat, 1);
            ItemMeta meta = pick.getItemMeta();
            if (meta != null) {
                try { meta.setUnbreakable(true); } catch (Throwable ignored) {}
                try {
                    meta.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING, session.getSessionId());
                } catch (Throwable t) {
                    errors.log("PDC not available for pickaxe (нужен 1.14+)", t);
                }
                meta.setDisplayName(messages.color("&bКапча-кирка"));
                pick.setItemMeta(meta);
            }
            player.getInventory().addItem(pick);
            // Обновить инвентарь (1.14+ ок)
            try { player.updateInventory(); } catch (Throwable ignored) {}
        } catch (Exception e) {
            errors.log("givePickaxe failed", e);
        }
    }

    /** Забрать кирку (и вообще очистить капча-инвентарь) при любом завершении. */
    private void takePickaxe(Player player) {
        try {
            Inventory inv = player.getInventory();
            Material pickMat = config.getPickaxeMaterial();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack it = inv.getItem(i);
                if (it == null) continue;
                if (it.getType() == pickMat) {
                    inv.setItem(i, null);
                    continue;
                }
                // Убрать всё капча-барахло (еда/мусор/шалкер) - полный клир
                // Но сохранённый инвентарь восстановим отдельно, так что чистим всё
            }
            // Полный клир проще и надёжнее
            inv.clear();
            try { player.getInventory().setArmorContents(new ItemStack[player.getInventory().getArmorContents().length]); } catch (Exception ignored) {}
            try { player.getInventory().setItemInOffHand(null); } catch (Throwable ignored) {}
            try { player.updateInventory(); } catch (Throwable ignored) {}
        } catch (Exception e) {
            errors.log("takePickaxe failed", e);
        }
    }

    // ==================== COUNTDOWN ====================

    private void startCountdown(final Player player, final CaptchaSession session) {
        final int total = config.getTimeoutSeconds();
        session.setTimeLeft(total);
        session.setDeadlineMillis(System.currentTimeMillis() + total * 1000L);

        session.cancelTask();
        session.setCountdownTask(new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (!player.isOnline()) {
                        cancel();
                        handleQuit(player.getUniqueId());
                        return;
                    }
                    if (!isActive(player.getUniqueId())) {
                        cancel();
                        return;
                    }
                    int left = session.getTimeLeft() - 1;
                    session.setTimeLeft(left);
                    if (left <= 0) {
                        cancel();
                        fail(player, "TIMEOUT");
                        return;
                    }
                    sendActionBar(player, messages.format("actionbar-timer",
                            new String[][]{{"{time}", String.valueOf(left)}}));
                } catch (Exception e) {
                    errors.log("countdown failed", e);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L));

        // Первый показ сразу
        sendActionBar(player, messages.format("actionbar-timer",
                new String[][]{{"{time}", String.valueOf(total)}}));
    }

    @SuppressWarnings("deprecation")
    private void sendActionBar(Player player, String text) {
        try {
            // Spigot API (org.bukkit.entity.Player#spigot) - доступен на Spigot/Paper/Purpur.
            // Чистый Bukkit API action bar не имеет, поэтому используем spigot() -
            // это часть дистрибутива Spigot/Paper, без NMS.
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
        } catch (Throwable t) {
            // Fallback: ничего не делаем (тихий), чтобы не спамить чат
        }
    }

    // ==================== CHECKS ====================

    public boolean isPickaxeInMainHand(Player player, CaptchaSession session) {
        try {
            ItemStack inHand;
            try {
                inHand = player.getInventory().getItemInMainHand();
            } catch (Throwable t) {
                inHand = player.getInventory().getItemInHand();
            }
            if (inHand == null || inHand.getType() == Material.AIR) return false;
            if (inHand.getType() != config.getPickaxeMaterial()) return false;
            // Дополнительно проверяем метку сессии (если PDC доступен)
            try {
                ItemMeta meta = inHand.getItemMeta();
                if (meta == null) return false;
                String tag = meta.getPersistentDataContainer().get(sessionKey, PersistentDataType.STRING);
                return session.getSessionId().equals(tag);
            } catch (Throwable t) {
                // PDC недоступен - достаточно материала
                return true;
            }
        } catch (Exception e) {
            errors.log("isPickaxe check failed", e);
            return false;
        }
    }

    public boolean isMarkedShulker(ItemStack item, CaptchaSession session) {
        if (item == null || item.getType() == Material.AIR) return false;
        if (item.getType() != config.getShulkerMaterial()) return false;
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return false;
            String tag = meta.getPersistentDataContainer().get(sessionKey, PersistentDataType.STRING);
            return session.getSessionId().equals(tag);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Сверить содержимое шалкера с ожидаемым. Возвращает null если успех, иначе код ошибки. */
    public String verifyShulkerContents(Map<Material, Integer> expected, ItemStack shulkerItem) {
        try {
            if (!(shulkerItem.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta)) {
                return "EXTRA_ITEM";
            }
            org.bukkit.inventory.meta.BlockStateMeta bsm =
                    (org.bukkit.inventory.meta.BlockStateMeta) shulkerItem.getItemMeta();
            if (bsm == null || !(bsm.getBlockState() instanceof ShulkerBox)) {
                return "EXTRA_ITEM";
            }
            ShulkerBox box = (ShulkerBox) bsm.getBlockState();
            Map<Material, Integer> actual = new HashMap<Material, Integer>();
            for (ItemStack it : box.getInventory().getContents()) {
                if (it == null || it.getType() == Material.AIR) continue;
                Material m = it.getType();
                actual.put(m, actual.getOrDefault(m, 0) + it.getAmount());
            }
            // Точное совпадение
            if (actual.equals(expected)) return null;

            // Лишний предмет: есть материал которого нет в ожидаемом
            for (Material m : actual.keySet()) {
                if (!expected.containsKey(m)) return "EXTRA_ITEM";
            }
            // Не вся еда / перебор
            for (Map.Entry<Material, Integer> e : expected.entrySet()) {
                int have = actual.getOrDefault(e.getKey(), 0);
                if (have < e.getValue()) return "NOT_ALL_FOOD";
                if (have > e.getValue()) return "EXTRA_ITEM";
            }
            // Если дошли сюда - размеры различаются (пустой? лишний?)
            if (actual.size() != expected.size()) return "EXTRA_ITEM";
            return "NOT_ALL_FOOD";
        } catch (Exception e) {
            errors.log("verifyShulkerContents failed", e);
            return "EXTRA_ITEM";
        }
    }

    // ==================== SUCCESS / FAIL / QUIT ====================

    public void success(Player player) {
        UUID id = player.getUniqueId();
        CaptchaSession session = activeMap.get(id);
        if (session == null || !isActive(id)) return;
        try {
            session.cancelTask();
            // Очистить воронку
            try {
                Block h = config.getHopperLocation(captchaWorld).getBlock();
                if (h.getState() instanceof Hopper) {
                    ((Hopper) h.getState()).getInventory().clear();
                    h.getState().update(true, false);
                }
            } catch (Exception e) {
                errors.log("success: hopper clear failed", e);
            }
            // Убрать дропы в зоне
            try {
                for (Entity en : captchaWorld.getEntitiesByClass(Item.class)) {
                    if (config.isInsideBounds(en.getLocation())) en.remove();
                }
            } catch (Exception ignored) {}

            takePickaxe(player);
            if (session != null) restorePlayerState(player, session);

            player.sendMessage(messages.get("success"));

            String mode = config.getMode();
            if ("LOCAL".equals(mode)) {
                Location loc = config.getLocalSpawn();
                if (loc.getWorld() == null) {
                    errors.log("LOCAL mode: world not found, teleport to captcha spawn", null);
                } else {
                    teleportBypass.add(id);
                    player.teleport(loc);
                    final UUID fid = id;
                    Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                        @Override public void run() { teleportBypass.remove(fid); }
                    }, 5L);
                }
            } else {
                // PROXY
                proxy.connect(player, config.getProxyServer());
            }
            plugin.getLogger().info("Player " + player.getName() + " passed captcha.");
        } catch (Exception e) {
            errors.log("success handling failed for " + player.getName(), e);
        } finally {
            cleanupSession(id, true);
            startNextInQueue();
        }
    }

    /** Ошибка капчи: кик (по умолчанию) + лог в консоль. */
    public void fail(Player player, String errorKey) {
        UUID id = player.getUniqueId();
        if (!isCaptchaPlayer(id)) return;
        // Только активный игрок может словить проверку содержимого,
        // но OUT_OF_BOUNDS/WRONG_INVENTORY могут быть и у ожидающих? - кикаем их тоже.
        boolean wasActive = isActive(id);
        CaptchaSession session = activeMap.get(id);
        // Ожидающий без сессии - просто кик
        try {
            String action = config.getAction(errorKey);
            String kickMsg = messages.kickReason(errorKey);

            // Перед киком восстановить инвентарь чтобы не вайпнуть (LOCAL)
            try {
                if (session != null && player.isOnline()) {
                    takePickaxe(player);
                    restorePlayerState(player, session);
                }
            } catch (Exception e) {
                errors.log("fail restore failed", e);
            }

            if ("KICK".equalsIgnoreCase(action)) {
                plugin.getLogger().info("HappyFilterCaptcha: kicked " + player.getName() + " reason " + errorKey);
                try {
                    // kickPlayer должен быть в main thread
                    if (Bukkit.isPrimaryThread()) {
                        player.kickPlayer(kickMsg);
                    } else {
                        final Player fp = player;
                        final String fmsg = kickMsg;
                        Bukkit.getScheduler().runTask(plugin, new Runnable() {
                            @Override public void run() { try { fp.kickPlayer(fmsg); } catch (Exception ignored) {} }
                        });
                    }
                } catch (Exception e) {
                    errors.log("kick failed", e);
                }
            } else {
                player.sendMessage(kickMsg);
            }
        } catch (Exception e) {
            errors.log("fail handling failed", e);
        } finally {
            cleanupSession(id, wasActive);
            if (wasActive) startNextInQueue();
            else {
                // Ожидающий выбыл - очередь просто сдвигается
            }
        }
    }

    /** Выход игрока во время капчи: без кика, сессия сбрасывается. */
    public void handleQuit(UUID id) {
        try {
            boolean wasActive = isActive(id);
            cleanupSession(id, false);
            if (wasActive) {
                // Сбросить комнату для следующего
                try { resetRoom(); } catch (Exception e) { errors.log("resetRoom after quit failed", e); }
                startNextInQueue();
            } else {
                queue.remove(id);
            }
        } catch (Exception e) {
            errors.log("handleQuit failed", e);
        }
    }

    private void cleanupSession(UUID id, boolean resetRoom) {
        try {
            CaptchaSession s = activeMap.remove(id);
            if (s != null) s.cancelTask();
            if (activePlayer != null && activePlayer.equals(id)) activePlayer = null;
            queue.remove(id);
            teleportBypass.remove(id);
            if (resetRoom) {
                try { resetRoom(); } catch (Exception e) { errors.log("resetRoom failed", e); }
            }
            // Убрать пикап-утилиты: игрок уже ушёл/кикнут, инвентарь чистить не нужно если оффлайн
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline() && resetRoom) {
                // Ничего - инвентарь уже восстановлен в success/fail
            }
        } catch (Exception e) {
            errors.log("cleanupSession failed", e);
        }
    }

    /** Сброс комнаты в исходное (пустой шалкер, пустая воронка, чистый сундук-заготовка). */
    public void resetRoom() {
        if (captchaWorld == null) return;
        try {
            Location shLoc = config.getShulkerLocation(captchaWorld);
            shLoc.getBlock().setType(config.getShulkerMaterial(), false);
            if (shLoc.getBlock().getState() instanceof ShulkerBox) {
                ShulkerBox box = (ShulkerBox) shLoc.getBlock().getState();
                box.getInventory().clear();
                box.update(true, false);
            }
        } catch (Exception e) {
            errors.log("resetRoom shulker failed", e);
        }
        try {
            Location hLoc = config.getHopperLocation(captchaWorld);
            Block hb = hLoc.getBlock();
            if (!(hb.getState() instanceof Hopper)) {
                hb.setType(Material.HOPPER, false);
            }
            if (hb.getState() instanceof Hopper) {
                ((Hopper) hb.getState()).getInventory().clear();
                hb.getState().update(true, false);
            }
            Block above = hLoc.clone().add(0, 1, 0).getBlock();
            if (!above.getType().isAir()) above.setType(Material.AIR, false);
        } catch (Exception e) {
            errors.log("resetRoom hopper failed", e);
        }
        try {
            Location cLoc = config.getChestLocation(captchaWorld);
            Block cb = cLoc.getBlock();
            if (!(cb.getState() instanceof Chest)) cb.setType(Material.CHEST, false);
            if (cb.getState() instanceof Chest) {
                ((Chest) cb.getState()).getInventory().clear();
                cb.getState().update(true, false);
            }
        } catch (Exception e) {
            errors.log("resetRoom chest failed", e);
        }
        try {
            for (Entity en : captchaWorld.getEntitiesByClass(Item.class)) {
                if (config.isInsideBounds(en.getLocation())) en.remove();
            }
        } catch (Exception ignored) {}
    }

    private void startNextInQueue() {
        if (activePlayer != null) return;
        while (!queue.isEmpty()) {
            UUID next = queue.peek();
            Player p = Bukkit.getPlayer(next);
            if (p == null || !p.isOnline()) {
                queue.poll();
                activeMap.remove(next);
                continue;
            }
            if (p.hasPermission("happyfilter.bypass")) {
                queue.poll();
                continue;
            }
            queue.poll();
            p.sendMessage(messages.get("queue-start"));
            startSession(p);
            return;
        }
        // Очередь пуста - комната свободна, сбросить
        try { resetRoom(); } catch (Exception e) { errors.log("resetRoom idle failed", e); }
    }

    // Для команды status
    public String describe(UUID id) {
        if (isActive(id)) {
            CaptchaSession s = activeMap.get(id);
            int t = s == null ? -1 : s.getTimeLeft();
            return "active timeLeft=" + t;
        }
        if (isWaiting(id)) return "waiting pos=" + queuePosition(id);
        return "free";
    }
}

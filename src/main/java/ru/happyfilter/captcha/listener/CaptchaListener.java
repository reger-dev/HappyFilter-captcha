package ru.happyfilter.captcha.listener;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Hopper;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.happyfilter.captcha.HappyFilterCaptcha;
import ru.happyfilter.captcha.config.ConfigManager;
import ru.happyfilter.captcha.messages.MessageManager;
import ru.happyfilter.captcha.model.CaptchaSession;
import ru.happyfilter.captcha.session.SessionManager;
import ru.happyfilter.captcha.util.ErrorLogger;

/**
 * Все запреты во время капчи. Только Bukkit API.
 */
public class CaptchaListener implements Listener {

    private final HappyFilterCaptcha plugin;
    private final ConfigManager config;
    private final MessageManager messages;
    private final SessionManager sessions;
    private final ErrorLogger errors;

    public CaptchaListener(HappyFilterCaptcha plugin, ConfigManager config,
                           MessageManager messages, SessionManager sessions, ErrorLogger errors) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.sessions = sessions;
        this.errors = errors;
    }

    private boolean inCaptcha(Player p) {
        if (p == null) return false;
        if (p.hasPermission("happyfilter.bypass")) return false;
        return sessions.isCaptchaPlayer(p.getUniqueId());
    }

    private boolean isActive(Player p) {
        return sessions.isActive(p.getUniqueId());
    }

    // ================= JOIN / QUIT =================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        try {
            Player p = e.getPlayer();
            if (p.hasPermission("happyfilter.bypass")) return;
            sessions.onJoin(p);
        } catch (Exception ex) {
            errors.log("onJoin failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        try {
            Player p = e.getPlayer();
            if (!sessions.isCaptchaPlayer(p.getUniqueId())) return;
            // Забрать кирку при выходе (п. ТЗ) + сброс без кика
            try {
                // Восстановить сохранённый инвентарь чтобы сервер сохранил его, а не пустой
                CaptchaSession s = sessions.getSession(p.getUniqueId());
                if (s != null) {
                    // Очистить капча-барахло
                    p.getInventory().clear();
                    if (s.getSavedContents() != null) p.getInventory().setContents(s.getSavedContents());
                    if (s.getSavedArmor() != null) p.getInventory().setArmorContents(s.getSavedArmor());
                }
            } catch (Exception ex) {
                errors.log("onQuit restore failed", ex);
            }
            sessions.handleQuit(p.getUniqueId());
        } catch (Exception ex) {
            errors.log("onQuit failed", ex);
        }
    }

    // ================= BLOCK BREAK / PLACE =================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent e) {
        try {
            Player p = e.getPlayer();
            if (!inCaptcha(p)) return;
            if (!isActive(p)) { e.setCancelled(true); return; }

            CaptchaSession s = sessions.getSession(p.getUniqueId());
            if (s == null) { e.setCancelled(true); return; }

            Location shLoc = config.getShulkerLocation(sessions.getCaptchaWorld());
            Block b = e.getBlock();
            if (!sameBlock(b.getLocation(), shLoc)) {
                e.setCancelled(true);
                return;
            }
            // Это капча-шалкер: можно только киркой в основной руке
            if (!sessions.isPickaxeInMainHand(p, s)) {
                e.setCancelled(true);
                sessions.fail(p, "NO_PICKAXE");
                return;
            }
            // Отменить ванильный дроп, создать помеченный предмет с содержимым
            e.setCancelled(true);
            try {
                if (!(b.getState() instanceof ShulkerBox)) {
                    sessions.fail(p, "NO_PICKAXE");
                    return;
                }
                ShulkerBox boxState = (ShulkerBox) b.getState();
                ItemStack[] contents = boxState.getInventory().getContents().clone();

                b.setType(Material.AIR, false);

                Material shMat = config.getShulkerMaterial();
                ItemStack shulkerItem = new ItemStack(shMat, 1);
                ItemMeta meta = shulkerItem.getItemMeta();
                if (meta instanceof BlockStateMeta) {
                    BlockStateMeta bsm = (BlockStateMeta) meta;
                    try {
                        if (bsm.getBlockState() instanceof ShulkerBox) {
                            ShulkerBox fresh = (ShulkerBox) bsm.getBlockState();
                            fresh.getInventory().setContents(contents);
                            bsm.setBlockState(fresh);
                        }
                    } catch (Exception ex) {
                        errors.log("shulker content copy failed", ex);
                    }
                    try {
                        bsm.getPersistentDataContainer().set(
                                sessions.getSessionKey(), PersistentDataType.STRING, s.getSessionId());
                    } catch (Throwable t) {
                        errors.log("PDC mark shulker failed", t);
                    }
                    shulkerItem.setItemMeta(bsm);
                }
                // Выбросить предмет на месте шалкера
                Location dropLoc = b.getLocation().clone().add(0.5, 0.5, 0.5);
                sessions.getCaptchaWorld().dropItemNaturally(dropLoc, shulkerItem);
            } catch (Exception ex) {
                errors.log("shulker break handling failed", ex);
            }
        } catch (Exception ex) {
            errors.log("onBreak failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent e) {
        try {
            if (!inCaptcha(e.getPlayer())) return;
            e.setCancelled(true);
        } catch (Exception ex) {
            errors.log("onPlace failed", ex);
        }
    }

    // ================= INVENTORIES =================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onOpen(InventoryOpenEvent e) {
        try {
            if (!(e.getPlayer() instanceof Player)) return;
            Player p = (Player) e.getPlayer();
            if (!inCaptcha(p)) return;
            if (!isActive(p)) { e.setCancelled(true); return; }

            Inventory inv = e.getInventory();
            InventoryHolder holder = inv.getHolder();
            Location chestLoc = config.getChestLocation(sessions.getCaptchaWorld());
            Location shLoc = config.getShulkerLocation(sessions.getCaptchaWorld());

            // Свой собственный инвентарь (CRAFTING) - разрешён (отдельного эвента нет, но на всякий)
            // InventoryType.CRAFTING с holder=Player - разрешаем
            if (holder instanceof Player) return;

            // Капча-сундук
            if (holder instanceof Chest) {
                Location l = ((Chest) holder).getLocation();
                if (l != null && sameBlock(l, chestLoc)) return;
                // чужой сундук
                e.setCancelled(true);
                sessions.fail(p, "WRONG_INVENTORY");
                return;
            }
            // Капча-шалкер (пока стоит)
            if (holder instanceof ShulkerBox) {
                try {
                    Location l = ((ShulkerBox) holder).getLocation();
                    if (l != null && sameBlock(l, shLoc)) return;
                } catch (Throwable ignored) {}
                e.setCancelled(true);
                sessions.fail(p, "WRONG_INVENTORY");
                return;
            }
            // Воронку открывать нельзя (попытка = ошибка)
            if (holder instanceof Hopper) {
                e.setCancelled(true);
                sessions.fail(p, "WRONG_INVENTORY");
                return;
            }
            // Всё остальное (печки, эндер-сундуки и т.д.) запрещено
            // Но свой инвентарь крафта уже отсеян выше. Эндерчест holder = Player? В 1.14+ EnderChest holder null?
            // Безопасно: запрещаем всё кроме сундука/шалкера/своего.
            e.setCancelled(true);
            // Чтобы не кикать за каждое открытие крафта при нажатии E (если вдруг придёт),
            // проверяем тип: CRAFTING от игрока уже разрешён. Остальное - ошибка.
            sessions.fail(p, "WRONG_INVENTORY");
        } catch (Exception ex) {
            errors.log("onOpen failed", ex);
        }
    }

    /** Проверка в момент засасывания предмета воронкой. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHopperPickup(InventoryPickupItemEvent e) {
        try {
            if (!(e.getInventory().getHolder() instanceof Hopper)) return;
            Hopper h = (Hopper) e.getInventory().getHolder();
            Location hLoc;
            try { hLoc = h.getLocation(); } catch (Exception ex) { return; }
            if (hLoc == null || hLoc.getWorld() == null) return;
            if (sessions.getCaptchaWorld() == null) return;
            if (!hLoc.getWorld().equals(sessions.getCaptchaWorld())) return;

            Location expectedHopper = config.getHopperLocation(sessions.getCaptchaWorld());
            if (!sameBlock(hLoc, expectedHopper)) return; // не наша воронка

            // Наша капча-воронка. Должен быть активный игрок.
            if (sessions.getActivePlayer() == null) { e.setCancelled(true); return; }
            Player p = plugin.getServer().getPlayer(sessions.getActivePlayer());
            if (p == null || !p.isOnline()) { e.setCancelled(true); return; }
            CaptchaSession s = sessions.getSession(p.getUniqueId());
            if (s == null) { e.setCancelled(true); return; }

            ItemStack item = e.getItem().getItemStack();
            // Метка не совпадает - ошибка
            if (!sessions.isMarkedShulker(item, s)) {
                e.setCancelled(true);
                sessions.fail(p, "WRONG_HOPPER_ITEM");
                return;
            }
            // Метка совпала - сверить содержимое
            String err = sessions.verifyShulkerContents(s.getExpectedFood(), item);
            e.setCancelled(true);
            if (err != null) {
                try { e.getItem().remove(); } catch (Exception ignored) {}
                sessions.fail(p, err);
                return;
            }
            // Успех
            try { e.getItem().remove(); } catch (Exception ignored) {}
            sessions.success(p);
        } catch (Exception ex) {
            errors.log("onHopperPickup failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent e) {
        try {
            Player p = e.getPlayer();
            if (!inCaptcha(p)) return;
            if (!isActive(p)) { e.setCancelled(true); return; }
            CaptchaSession s = sessions.getSession(p.getUniqueId());
            if (s == null) { e.setCancelled(true); return; }
            ItemStack dropped = e.getItemDrop().getItemStack();
            // Разрешён выброс только помеченного капча-шалкера
            if (sessions.isMarkedShulker(dropped, s)) return;
            e.setCancelled(true);
            sessions.fail(p, "WRONG_DROP");
        } catch (Exception ex) {
            errors.log("onDrop failed", ex);
        }
    }

    // ================= CHAT / COMMANDS =================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onChat(AsyncPlayerChatEvent e) {
        try {
            Player p = e.getPlayer();
            if (!sessions.isCaptchaPlayer(p.getUniqueId())) return;
            if (p.hasPermission("happyfilter.bypass")) return;
            e.setCancelled(true);
        } catch (Exception ex) {
            errors.log("onChat failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        try {
            Player p = e.getPlayer();
            if (!sessions.isCaptchaPlayer(p.getUniqueId())) return;
            if (p.hasPermission("happyfilter.bypass")) return;
            String msg = e.getMessage().toLowerCase();
            // Кроме /happycaptcha для админов
            if ((msg.startsWith("/happycaptcha") || msg.startsWith("/happyc")
                    || msg.startsWith("/hfc")) && p.hasPermission("happyfilter.admin")) {
                return;
            }
            e.setCancelled(true);
        } catch (Exception ex) {
            errors.log("onCommand failed", ex);
        }
    }

    // ================= FOOD / CRAFT / BUCKETS =================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsume(PlayerItemConsumeEvent e) {
        try {
            if (!inCaptcha(e.getPlayer())) return;
            e.setCancelled(true);
        } catch (Exception ex) {
            errors.log("onConsume failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCraft(CraftItemEvent e) {
        try {
            if (!(e.getWhoClicked() instanceof Player)) return;
            Player p = (Player) e.getWhoClicked();
            if (!inCaptcha(p)) return;
            e.setCancelled(true);
        } catch (Exception ex) {
            errors.log("onCraft failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketFill(PlayerBucketFillEvent e) {
        try {
            if (!inCaptcha(e.getPlayer())) return;
            e.setCancelled(true);
        } catch (Exception ex) {
            errors.log("onBucketFill failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        try {
            if (!inCaptcha(e.getPlayer())) return;
            e.setCancelled(true);
        } catch (Exception ex) {
            errors.log("onBucketEmpty failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        try {
            Player p = e.getPlayer();
            if (!inCaptcha(p)) return;
            // Блокируем использование вёдер (клик с ведром)
            try {
                if (e.getItem() != null) {
                    Material t = e.getItem().getType();
                    if (t == Material.WATER_BUCKET || t == Material.LAVA_BUCKET
                            || t == Material.BUCKET
                            || t.name().contains("BUCKET")) {
                        e.setCancelled(true);
                        return;
                    }
                }
            } catch (Exception ignored) {}
            // Открытие воронки ПКМ тоже ловим здесь (дополнительно к InventoryOpenEvent)
            try {
                if (e.getClickedBlock() != null) {
                    Location hLoc = config.getHopperLocation(sessions.getCaptchaWorld());
                    if (sameBlock(e.getClickedBlock().getLocation(), hLoc)) {
                        // Даём InventoryOpenEvent сработать, но если клиент не открыл - всё равно ошибка?
                        // Не кикаем здесь чтобы не дублировать; InventoryOpenEvent кикнет.
                    }
                }
            } catch (Exception ignored) {}
        } catch (Exception ex) {
            errors.log("onInteract failed", ex);
        }
    }

    // ================= TELEPORT / BOUNDS =================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent e) {
        try {
            Player p = e.getPlayer();
            if (!sessions.isCaptchaPlayer(p.getUniqueId())) return;
            if (p.hasPermission("happyfilter.bypass")) return;
            if (sessions.allowTeleport(p.getUniqueId())) return;
            e.setCancelled(true);
        } catch (Exception ex) {
            errors.log("onTeleport failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent e) {
        try {
            Player p = e.getPlayer();
            if (!sessions.isCaptchaPlayer(p.getUniqueId())) return;
            if (p.hasPermission("happyfilter.bypass")) return;

            // Ожидающие заморожены полностью (кроме поворота головы)
            if (!isActive(p)) {
                Location from = e.getFrom();
                Location to = e.getTo();
                if (to == null) return;
                if (from.getBlockX() != to.getBlockX()
                        || from.getBlockY() != to.getBlockY()
                        || from.getBlockZ() != to.getBlockZ()) {
                    e.setCancelled(true);
                }
                return;
            }

            // Активный: выход за границы = ошибка
            Location to = e.getTo();
            if (to == null) return;
            if (!to.getWorld().equals(sessions.getCaptchaWorld())) {
                sessions.fail(p, "OUT_OF_BOUNDS");
                return;
            }
            if (!config.isInsideBounds(to)) {
                sessions.fail(p, "OUT_OF_BOUNDS");
            }
        } catch (Exception ex) {
            errors.log("onMove failed", ex);
        }
    }

    // ================= DAMAGE / HUNGER / PICKUP =================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        try {
            if (!(e.getEntity() instanceof Player)) return;
            Player p = (Player) e.getEntity();
            if (!inCaptcha(p)) return;
            e.setCancelled(true);
        } catch (Exception ex) {
            errors.log("onDamage failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onFood(FoodLevelChangeEvent e) {
        try {
            if (!(e.getEntity() instanceof Player)) return;
            Player p = (Player) e.getEntity();
            if (!inCaptcha(p)) return;
            e.setCancelled(true);
        } catch (Exception ex) {
            errors.log("onFood failed", ex);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent e) {
        // Подбор предметов не запрещён ТЗ явно, разрешаем (игрок может подобрать свой шалкер обратно).
        // Но запрещаем подбор чужих дропов ожидающим.
        try {
            if (!(e.getEntity() instanceof Player)) return;
            Player p = (Player) e.getEntity();
            if (!inCaptcha(p)) return;
            if (!isActive(p)) e.setCancelled(true);
        } catch (Exception ex) {
            errors.log("onPickup failed", ex);
        }
    }

    private boolean sameBlock(Location a, Location b) {
        if (a == null || b == null) return false;
        if (a.getWorld() == null || b.getWorld() == null) return false;
        if (!a.getWorld().equals(b.getWorld())) return false;
        return a.getBlockX() == b.getBlockX()
                && a.getBlockY() == b.getBlockY()
                && a.getBlockZ() == b.getBlockZ();
    }
}

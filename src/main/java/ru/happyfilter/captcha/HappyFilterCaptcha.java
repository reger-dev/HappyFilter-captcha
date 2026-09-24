package ru.happyfilter.captcha;

import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.happyfilter.captcha.command.CaptchaCommand;
import ru.happyfilter.captcha.config.ConfigManager;
import ru.happyfilter.captcha.listener.CaptchaListener;
import ru.happyfilter.captcha.messages.MessageManager;
import ru.happyfilter.captcha.proxy.ProxyService;
import ru.happyfilter.captcha.session.SessionManager;
import ru.happyfilter.captcha.util.ErrorLogger;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * HappyFilterCaptcha - главный класс.
 * Включает логику копирования и загрузки мира из папки World.
 *
 * Поведение при старте:
 *  1) Если мир с именем из конфига (captcha.world) уже загружен - использовать его.
 *  2) Иначе, если в plugins/HappyFilterCaptcha/ есть папка World - скопировать её
 *     в корень сервера под именем из конфига и загрузить через WorldCreator.
 *  3) Если ни мира, ни папки World нет - ошибка в errors.log и консоль, отключение.
 * Существующий мир сервера НЕ перезаписывается.
 */
public class HappyFilterCaptcha extends JavaPlugin {

    private static HappyFilterCaptcha instance;

    private ErrorLogger errorLogger;
    private ConfigManager configManager;
    private MessageManager messageManager;
    private SessionManager sessionManager;
    private ProxyService proxyService;

    public static HappyFilterCaptcha getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        // errors.log раньше всех
        errorLogger = new ErrorLogger(this);

        try {
            // Дефолтные конфиги
            saveDefaultConfig();
            File msgFile = new File(getDataFolder(), "messages.yml");
            if (!msgFile.exists()) {
                saveResource("messages.yml", false);
            }

            configManager = new ConfigManager(this, errorLogger);
            messageManager = new MessageManager(this, errorLogger);
            messageManager.load();
            proxyService = new ProxyService(this, errorLogger);
            sessionManager = new SessionManager(this, configManager, messageManager, proxyService, errorLogger);

            // --- Мир капчи ---
            World captchaWorld = loadCaptchaWorld();
            if (captchaWorld == null) {
                String err = "HappyFilterCaptcha: captcha world not found and plugins/HappyFilterCaptcha/World missing. Disabling.";
                getLogger().severe(err);
                errorLogger.log(err, null);
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            applyWorldSettings(captchaWorld);
            sessionManager.init(captchaWorld);

            // BungeeCord канал (Connect) - работает и за Velocity
            try {
                getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            } catch (Exception e) {
                errorLogger.log("Failed to register BungeeCord channel", e);
            }

            // Слушатели и команда
            getServer().getPluginManager().registerEvents(
                    new CaptchaListener(this, configManager, messageManager, sessionManager, errorLogger), this);

            CaptchaCommand cmd = new CaptchaCommand(this, configManager, messageManager, sessionManager);
            try {
                getCommand("happycaptcha").setExecutor(cmd);
                getCommand("happycaptcha").setTabCompleter(cmd);
            } catch (Exception e) {
                errorLogger.log("Failed to register /happycaptcha", e);
            }

            // Игроки уже онлайн (например /reload) - загнать в капчу
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    if (!p.hasPermission("happyfilter.bypass")) {
                        sessionManager.onJoin(p);
                    }
                } catch (Exception e) {
                    errorLogger.log("onEnable online-player enqueue failed: " + p.getName(), e);
                }
            }

            getLogger().info("HappyFilterCaptcha enabled. World: " + captchaWorld.getName());

        } catch (Exception e) {
            if (errorLogger != null) errorLogger.log("onEnable failed", e);
            getLogger().severe("HappyFilterCaptcha enable failed: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (getServer() != null) {
                try {
                    getServer().getMessenger().unregisterOutgoingPluginChannel(this);
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            if (errorLogger != null) errorLogger.log("onDisable failed", e);
        }
        instance = null;
    }

    public ConfigManager getConfigManager() { return configManager; }
    public MessageManager getMessageManager() { return messageManager; }
    public SessionManager getSessionManager() { return sessionManager; }
    public ErrorLogger getErrorLogger() { return errorLogger; }

    // ==================== МИР ====================

    /**
     * Загрузка мира капчи по правилам ТЗ.
     * @return загруженный мир или null
     */
    private World loadCaptchaWorld() {
        String worldName = getConfig().getString("captcha.world", "captcha");
        if (worldName == null || worldName.isEmpty()) worldName = "captcha";

        // 1) Уже загружен - использовать
        World loaded = Bukkit.getWorld(worldName);
        if (loaded != null) {
            getLogger().info("Using already loaded captcha world: " + worldName);
            return loaded;
        }

        // Папка мира на диске сервера уже есть (но не загружен)? - просто загрузить, НЕ перезаписывать
        File destDir = new File(Bukkit.getWorldContainer(), worldName);
        if (destDir.exists()) {
            getLogger().info("Found existing world folder ./" + worldName + ", loading (not overwriting)...");
            try {
                World w = new WorldCreator(worldName).createWorld();
                if (w != null) return w;
                getLogger().warning("WorldCreator returned null for existing folder " + worldName);
            } catch (Exception e) {
                errorLogger.log("Failed to load existing world " + worldName, e);
            }
            // Продолжаем: попробуем скопировать из plugins? Нет - существующий НЕ перезаписываем.
            // Если загрузка не удалась - ошибка.
            String err = "Failed to load existing world ./" + worldName;
            getLogger().severe(err);
            errorLogger.log(err, null);
            return null;
        }

        // 2) Папка World в папке плагина - скопировать и загрузить
        File sourceDir = new File(getDataFolder(), "World");
        if (sourceDir.exists() && sourceDir.isDirectory()) {
            getLogger().info("Copying plugins/HappyFilterCaptcha/World -> ./" + worldName + " ...");
            try {
                copyWorldFolder(sourceDir.toPath(), destDir.toPath());
            } catch (Exception e) {
                String err = "Failed to copy World folder to ./" + worldName;
                getLogger().severe(err);
                errorLogger.log(err, e);
                return null;
            }
            try {
                // Удалить uid.dat если скопировался (чтобы не было конфликта ID миров)
                File uid = new File(destDir, "uid.dat");
                if (uid.exists()) {
                    if (!uid.delete()) {
                        getLogger().warning("Cannot delete uid.dat, world may conflict on ID");
                    }
                }
                World w = new WorldCreator(worldName).createWorld();
                if (w != null) {
                    getLogger().info("Captcha world copied and loaded: " + worldName);
                    return w;
                }
                String err = "WorldCreator returned null for " + worldName;
                getLogger().severe(err);
                errorLogger.log(err, null);
                return null;
            } catch (Exception e) {
                errorLogger.log("Failed to create world " + worldName, e);
                return null;
            }
        }

        // 3) Ни мира, ни папки - ошибка
        String err = "No captcha world '" + worldName + "' and no plugins/HappyFilterCaptcha/World folder.";
        getLogger().severe(err);
        errorLogger.log(err, null);
        return null;
    }

    /**
     * Рекурсивное копирование мира. session.lock пропускаем.
     * Только Bukkit/Java API, без NMS.
     */
    private void copyWorldFolder(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Path dest = target.resolve(rel.toString());
                if (!Files.exists(dest)) {
                    Files.createDirectories(dest);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                // session.lock не копируем - он создаётся сервером
                if (name.equals("session.lock")) {
                    return FileVisitResult.CONTINUE;
                }
                Path rel = source.relativize(file);
                Path dest = target.resolve(rel.toString());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Настройки мира: PEACEFUL, без мобов, всегда загружен. Только Bukkit API. */
    private void applyWorldSettings(World world) {
        try {
            world.setDifficulty(Difficulty.PEACEFUL);
        } catch (Exception e) {
            errorLogger.log("setDifficulty failed", e);
        }
        try {
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        } catch (Exception e) {
            errorLogger.log("setGameRule DO_MOB_SPAWNING failed", e);
        }
        try {
            world.setKeepSpawnInMemory(true);
        } catch (Exception e) {
            errorLogger.log("setKeepSpawnInMemory failed", e);
        }
        try {
            world.setAutoSave(false);
        } catch (Exception e) {
            errorLogger.log("setAutoSave failed", e);
        }
        try {
            world.setStorm(false);
            world.setThundering(false);
            world.setTime(6000L);
        } catch (Exception e) {
            errorLogger.log("weather reset failed", e);
        }
        try {
            // Прогрузить спавн-чанк капчи чтобы телепорты не проваливались
            String wName = world.getName();
            int sx = getConfig().getInt("captcha.spawn.x", 173);
            int sz = getConfig().getInt("captcha.spawn.z", -185);
            // chunk load через блок
            world.getBlockAt(sx, 174, sz).getChunk().load(true);
            getLogger().info("Captcha world " + wName + " ready (PEACEFUL, no mobs, keep loaded).");
        } catch (Exception e) {
            errorLogger.log("spawn chunk load failed", e);
        }
    }
}

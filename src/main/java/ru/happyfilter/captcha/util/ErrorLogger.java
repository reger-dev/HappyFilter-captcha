package ru.happyfilter.captcha.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Логгер внутренних ошибок плагина в plugins/HappyFilterCaptcha/errors.log.
 * Кики по капче сюда НЕ пишутся (они идут в консоль).
 */
public class ErrorLogger {

    private final JavaPlugin plugin;
    private final File file;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public ErrorLogger(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "errors.log");
    }

    public synchronized void log(String message) {
        write(message, null);
    }

    public synchronized void log(String message, Throwable t) {
        write(message, t);
    }

    public synchronized void log(Throwable t) {
        write(t == null ? "Unknown error" : t.toString(), t);
    }

    private void write(String message, Throwable t) {
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            if (!file.exists()) {
                file.createNewFile();
            }
            FileWriter fw = new FileWriter(file, true);
            PrintWriter pw = new PrintWriter(fw);
            try {
                pw.println("[" + fmt.format(new Date()) + "] " + message);
                if (t != null) {
                    t.printStackTrace(pw);
                }
            } finally {
                pw.close();
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Cannot write errors.log: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

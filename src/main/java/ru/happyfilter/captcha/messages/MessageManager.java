package ru.happyfilter.captcha.messages;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.happyfilter.captcha.util.ErrorLogger;

import java.io.File;

/**
 * Менеджер messages.yml с поддержкой цветов (&).
 */
public class MessageManager {

    private final JavaPlugin plugin;
    private final ErrorLogger errors;
    private File file;
    private FileConfiguration cfg;

    public MessageManager(JavaPlugin plugin, ErrorLogger errors) {
        this.plugin = plugin;
        this.errors = errors;
    }

    public void load() {
        try {
            file = new File(plugin.getDataFolder(), "messages.yml");
            if (!file.exists()) {
                plugin.saveResource("messages.yml", false);
            }
            cfg = YamlConfiguration.loadConfiguration(file);
        } catch (Exception e) {
            errors.log("Failed to load messages.yml", e);
            cfg = new YamlConfiguration();
        }
    }

    public void reload() {
        load();
    }

    public String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public String get(String key) {
        if (cfg == null) return key;
        String s = cfg.getString(key, key);
        return color(s);
    }

    public String get(String key, String def) {
        if (cfg == null) return color(def);
        return color(cfg.getString(key, def));
    }

    public String format(String key, String[][] placeholders) {
        String s = get(key);
        if (placeholders != null) {
            for (String[] ph : placeholders) {
                if (ph.length >= 2) s = s.replace(ph[0], ph[1]);
            }
        }
        return s;
    }

    public void send(Player p, String key) {
        p.sendMessage(get(key));
    }

    public void send(CommandSender s, String key) {
        s.sendMessage(get(key));
    }

    public String kickReason(String errorKey) {
        return get("kick-" + errorKey, get("kick-TIMEOUT", "&bHappyFilter &8-> &cCaptcha failed."));
    }
}

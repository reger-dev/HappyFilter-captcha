package ru.happyfilter.captcha.proxy;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.happyfilter.captcha.util.ErrorLogger;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 * Отправка игрока на другой сервер через BungeeCord plugin messaging (Connect).
 * Работает и за BungeeCord, и за Velocity (Velocity поддерживает BungeeCord-канал).
 * Только Bukkit API + JDK, без NMS и без Guava.
 */
public class ProxyService {

    private final JavaPlugin plugin;
    private final ErrorLogger errors;

    public ProxyService(JavaPlugin plugin, ErrorLogger errors) {
        this.plugin = plugin;
        this.errors = errors;
    }

    public void connect(Player player, String server) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            out.writeUTF("Connect");
            out.writeUTF(server);
            out.close();
            player.sendPluginMessage(plugin, "BungeeCord", b.toByteArray());
        } catch (Exception e) {
            errors.log("Proxy connect failed for " + player.getName() + " -> " + server, e);
        }
    }
}

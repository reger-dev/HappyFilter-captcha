package ru.happyfilter.velocity;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Гейт: первый вход -> captcha, lobby только из captcha.
 * Верифицированным считается игрок, который перешёл captcha -> lobby
 * (это делает backend HappyFilterCaptcha через BungeeCord Connect).
 */
public class GateListener {

    private final HappyFilterVelocity plugin;
    private final ProxyServer proxy;
    private final Logger logger;

    // UUID -> verified (прошёл капчу = перешёл captcha->lobby хотя бы раз за сессию)
    private final Map<UUID, Boolean> verified = new ConcurrentHashMap<>();

    public GateListener(HappyFilterVelocity plugin, ProxyServer proxy, Logger logger) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onPreConnect(ServerPreConnectEvent e) {
        Player p = e.getPlayer();
        String captcha = plugin.getCaptchaServer();
        String lobby = plugin.getLobbyServer();

        // Куда хочет: initial = empty current
        String current = p.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse(null);
        String target = null;
        if (e.getOriginalServer() != null) {
            target = e.getOriginalServer().getServerInfo().getName();
        } else if (e.getResult().getServer().isPresent()) {
            target = e.getResult().getServer().get().getServerInfo().getName();
        }

        // 1) Первый вход (current == null): всегда на captcha
        if (current == null) {
            if (!captcha.equals(target)) {
                Optional<RegisteredServer> cap = proxy.getServer(captcha);
                if (cap.isPresent()) {
                    e.setResult(ServerPreConnectEvent.ServerResult.allowed(cap.get()));
                } else {
                    p.disconnect(Component.text("Captcha server offline, try later.", NamedTextColor.RED));
                    e.setResult(ServerPreConnectEvent.ServerResult.denied());
                }
            }
            return;
        }

        // 2) captcha -> lobby: разрешить и пометить verified
        if (captcha.equals(current) && lobby.equals(target)) {
            verified.put(p.getUniqueId(), true);
            logger.info("HappyFilter: " + p.getUsername() + " captcha -> lobby (verified)");
            return; // allowed по умолчанию
        }

        // 3) Прямой рывок в lobby минуя капчу: вернуть на капчу
        if (lobby.equals(target) && !captcha.equals(current)
                && !verified.getOrDefault(p.getUniqueId(), false)) {
            Optional<RegisteredServer> cap = proxy.getServer(captcha);
            if (cap.isPresent()) {
                p.sendMessage(Component.text("Сначала пройди капчу!", NamedTextColor.YELLOW));
                e.setResult(ServerPreConnectEvent.ServerResult.allowed(cap.get()));
            } else {
                e.setResult(ServerPreConnectEvent.ServerResult.denied());
            }
        }
        // Остальное (lobby->lobby, verified) - разрешаем
    }

    @Subscribe
    public void onChat(PlayerChatEvent e) {
        if (!plugin.isBlockChat()) return;
        Player p = e.getPlayer();
        if (isUnverifiedInCaptcha(p)) {
            e.setResult(PlayerChatEvent.ChatResult.denied());
            p.sendMessage(Component.text("Чат заблокирован до конца капчи.", NamedTextColor.RED));
        }
    }

    @Subscribe
    public void onCommand(CommandExecuteEvent e) {
        if (!plugin.isBlockCommands()) return;
        if (!(e.getCommandSource() instanceof Player)) return;
        Player p = (Player) e.getCommandSource();
        if (!isUnverifiedInCaptcha(p)) return;

        String cmd = e.getCommand().toLowerCase().trim();
        // Разрешаем только уход обратно на капчу
        if (cmd.equals("server " + plugin.getCaptchaServer())
                || cmd.equals("hub " + plugin.getCaptchaServer())) {
            return;
        }
        e.setResult(CommandExecuteEvent.CommandResult.denied());
        p.sendMessage(Component.text("Команды заблокированы до конца капчи.", NamedTextColor.RED));
    }

    @Subscribe
    public void onQuit(DisconnectEvent e) {
        // Сессия сбрасывается: при следующем входе капча заново
        verified.remove(e.getPlayer().getUniqueId());
    }

    private boolean isUnverifiedInCaptcha(Player p) {
        if (verified.getOrDefault(p.getUniqueId(), false)) return false;
        String captcha = plugin.getCaptchaServer();
        return p.getCurrentServer()
                .map(s -> s.getServerInfo().getName().equals(captcha))
                .orElse(false);
    }
}

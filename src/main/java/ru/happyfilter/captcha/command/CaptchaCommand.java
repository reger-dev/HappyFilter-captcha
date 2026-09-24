package ru.happyfilter.captcha.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ru.happyfilter.captcha.HappyFilterCaptcha;
import ru.happyfilter.captcha.config.ConfigManager;
import ru.happyfilter.captcha.messages.MessageManager;
import ru.happyfilter.captcha.session.SessionManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * /happycaptcha reload | test [player] | status [player]
 */
public class CaptchaCommand implements CommandExecutor, TabCompleter {

    private final HappyFilterCaptcha plugin;
    private final ConfigManager config;
    private final MessageManager messages;
    private final SessionManager sessions;

    public CaptchaCommand(HappyFilterCaptcha plugin, ConfigManager config,
                          MessageManager messages, SessionManager sessions) {
        this.plugin = plugin;
        this.config = config;
        this.messages = messages;
        this.sessions = sessions;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("happyfilter.admin")) {
            sender.sendMessage(messages.get("cmd-no-perm"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(messages.get("cmd-usage"));
            return true;
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("reload")) {
            config.reload();
            messages.reload();
            sender.sendMessage(messages.get("cmd-reloaded"));
            return true;
        }
        if (sub.equals("status")) {
            Player target;
            if (args.length >= 2) {
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(messages.get("cmd-player-not-found"));
                    return true;
                }
            } else {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(messages.get("cmd-usage"));
                    return true;
                }
                target = (Player) sender;
            }
            if (sessions.isActive(target.getUniqueId())) {
                int t = sessions.getSession(target.getUniqueId()) == null ? -1
                        : sessions.getSession(target.getUniqueId()).getTimeLeft();
                String s = messages.format("cmd-status-active", new String[][]{
                        {"{player}", target.getName()},
                        {"{time}", String.valueOf(t)},
                        {"{queue}", String.valueOf(sessions.getQueueSize())},
                        {"{active}", String.valueOf(sessions.getActivePlayer())}
                });
                sender.sendMessage(s);
            } else if (sessions.isWaiting(target.getUniqueId())) {
                sender.sendMessage(messages.format("cmd-status-waiting", new String[][]{
                        {"{player}", target.getName()},
                        {"{pos}", String.valueOf(sessions.queuePosition(target.getUniqueId()))}
                }));
            } else {
                sender.sendMessage(messages.format("cmd-not-in-captcha", new String[][]{
                        {"{player}", target.getName()}
                }));
            }
            return true;
        }
        if (sub.equals("test")) {
            Player target;
            if (args.length >= 2) {
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(messages.get("cmd-player-not-found"));
                    return true;
                }
            } else {
                if (!(sender instanceof Player)) {
                    sender.sendMessage(messages.get("cmd-usage"));
                    return true;
                }
                target = (Player) sender;
            }
            if (target.hasPermission("happyfilter.bypass") && !sender.hasPermission("happyfilter.admin")) {
                sender.sendMessage(messages.get("cmd-test-fail-bypass"));
                return true;
            }
            // Если комната занята другим игроком - не ломаем чужую сессию
            if (sessions.getActivePlayer() != null && !sessions.getActivePlayer().equals(target.getUniqueId())) {
                sender.sendMessage("Room is busy. Active: " + sessions.getActivePlayer()
                        + ", queue: " + sessions.getQueueSize());
                return true;
            }
            // Снять bypass-игнор на время теста? Тест запускаем даже с bypass (админ захотел)
            sessions.forceStart(target);
            sender.sendMessage(messages.format("cmd-test-start", new String[][]{
                    {"{player}", target.getName()}
            }));
            return true;
        }
        sender.sendMessage(messages.get("cmd-usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (!sender.hasPermission("happyfilter.admin")) return new ArrayList<String>();
        if (args.length == 1) {
            return filter(Arrays.asList("reload", "test", "status"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("test") || args[0].equalsIgnoreCase("status"))) {
            List<String> names = new ArrayList<String>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[1]);
        }
        return new ArrayList<String>();
    }

    private List<String> filter(List<String> in, String prefix) {
        List<String> out = new ArrayList<String>();
        String low = prefix.toLowerCase();
        for (String s : in) {
            if (s.toLowerCase().startsWith(low)) out.add(s);
        }
        return out;
    }
}

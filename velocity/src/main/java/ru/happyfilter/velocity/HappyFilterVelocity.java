package ru.happyfilter.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * HappyFilterVelocity - гейт на прокси.
 * Логика без изменений backend-плагина:
 *  - первый вход всегда на captcha-сервер;
 *  - переход в lobby разрешён только если текущий сервер == captcha
 *    (именно так backend делает BungeeCord Connect lobby после успеха);
 *  - ручной /server lobby из ниоткуда (минуя капчу) возвращает обратно на капчу;
 *  - чат/команды до верификации блокируются на прокси.
 */
@Plugin(
        id = "happyfiltercaptcha",
        name = "HappyFilterVelocity",
        version = "1.0.0",
        description = "Force captcha first, lobby only via captcha.",
        authors = {"HappyFilter"}
)
public class HappyFilterVelocity {

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;

    private String captchaServer = "captcha";
    private String lobbyServer = "lobby";
    private boolean blockChat = true;
    private boolean blockCommands = true;

    @Inject
    public HappyFilterVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent e) {
        loadConfig();
        proxy.getEventManager().register(this, new GateListener(this, proxy, logger));
        logger.info("HappyFilterVelocity enabled: captcha=" + captchaServer + " lobby=" + lobbyServer);
    }

    private void loadConfig() {
        try {
            if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
            Path file = dataDir.resolve("happyfilter.properties");
            Properties p = new Properties();
            if (!Files.exists(file)) {
                p.setProperty("captcha-server", "captcha");
                p.setProperty("lobby-server", "lobby");
                p.setProperty("block-chat", "true");
                p.setProperty("block-commands", "true");
                try (OutputStream out = Files.newOutputStream(file)) {
                    p.store(out, "HappyFilterVelocity: captcha first, lobby only via captcha");
                }
            } else {
                try (InputStream in = Files.newInputStream(file)) {
                    p.load(in);
                }
            }
            captchaServer = p.getProperty("captcha-server", "captcha").trim();
            lobbyServer = p.getProperty("lobby-server", "lobby").trim();
            blockChat = Boolean.parseBoolean(p.getProperty("block-chat", "true"));
            blockCommands = Boolean.parseBoolean(p.getProperty("block-commands", "true"));
        } catch (IOException ex) {
            logger.warn("Cannot load happyfilter.properties, using defaults", ex);
        }
    }

    public String getCaptchaServer() { return captchaServer; }
    public String getLobbyServer() { return lobbyServer; }
    public boolean isBlockChat() { return blockChat; }
    public boolean isBlockCommands() { return blockCommands; }
}

package ru.happyfilter.captcha.config;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import ru.happyfilter.captcha.util.ErrorLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Менеджер config.yml. Только Bukkit API.
 */
public class ConfigManager {

    private final JavaPlugin plugin;
    private final ErrorLogger errors;

    public ConfigManager(JavaPlugin plugin, ErrorLogger errors) {
        this.plugin = plugin;
        this.errors = errors;
    }

    public void reload() {
        plugin.reloadConfig();
    }

    public String getCaptchaWorld() {
        return plugin.getConfig().getString("captcha.world", "captcha");
    }

    public Location getCaptchaSpawn(World world) {
        double x = plugin.getConfig().getDouble("captcha.spawn.x", 173.5);
        double y = plugin.getConfig().getDouble("captcha.spawn.y", 174.0);
        double z = plugin.getConfig().getDouble("captcha.spawn.z", -185.5);
        float yaw = (float) plugin.getConfig().getDouble("captcha.spawn.yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble("captcha.spawn.pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    public Location getChestLocation(World world) {
        return new Location(world,
                plugin.getConfig().getInt("captcha.chest.x"),
                plugin.getConfig().getInt("captcha.chest.y"),
                plugin.getConfig().getInt("captcha.chest.z"));
    }

    public Location getHopperLocation(World world) {
        return new Location(world,
                plugin.getConfig().getInt("captcha.hopper.x"),
                plugin.getConfig().getInt("captcha.hopper.y"),
                plugin.getConfig().getInt("captcha.hopper.z"));
    }

    public Location getShulkerLocation(World world) {
        return new Location(world,
                plugin.getConfig().getInt("captcha.shulker.x"),
                plugin.getConfig().getInt("captcha.shulker.y"),
                plugin.getConfig().getInt("captcha.shulker.z"));
    }

    public int getMinX() { return plugin.getConfig().getInt("captcha.bounds.min.x"); }
    public int getMinY() { return plugin.getConfig().getInt("captcha.bounds.min.y"); }
    public int getMinZ() { return plugin.getConfig().getInt("captcha.bounds.min.z"); }
    public int getMaxX() { return plugin.getConfig().getInt("captcha.bounds.max.x"); }
    public int getMaxY() { return plugin.getConfig().getInt("captcha.bounds.max.y"); }
    public int getMaxZ() { return plugin.getConfig().getInt("captcha.bounds.max.z"); }

    public boolean isInsideBounds(Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();
        int minX = Math.min(getMinX(), getMaxX());
        int maxX = Math.max(getMinX(), getMaxX());
        int minY = Math.min(getMinY(), getMaxY());
        int maxY = Math.max(getMinY(), getMaxY());
        int minZ = Math.min(getMinZ(), getMaxZ());
        int maxZ = Math.max(getMinZ(), getMaxZ());
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public Material getPickaxeMaterial() {
        String s = plugin.getConfig().getString("captcha.pickaxe-material", "DIAMOND_PICKAXE");
        try {
            return Material.valueOf(s.toUpperCase());
        } catch (Exception e) {
            errors.log("Bad pickaxe-material: " + s + ", fallback DIAMOND_PICKAXE", e);
            return Material.DIAMOND_PICKAXE;
        }
    }

    public Material getShulkerMaterial() {
        String s = plugin.getConfig().getString("captcha.shulker-material", "SHULKER_BOX");
        try {
            Material m = Material.valueOf(s.toUpperCase());
            return m;
        } catch (Exception e) {
            errors.log("Bad shulker-material: " + s + ", fallback SHULKER_BOX", e);
            // SHULKER_BOX существует с 1.11, безопасен для 1.14-1.21
            return Material.valueOf("SHULKER_BOX");
        }
    }

    public int getTimeoutSeconds() {
        return plugin.getConfig().getInt("captcha.timeout-seconds", 30);
    }

    public List<Material> getFoodPool() {
        List<String> raw = plugin.getConfig().getStringList("captcha.food-pool");
        List<Material> out = new ArrayList<Material>();
        if (raw == null || raw.isEmpty()) {
            raw = defaultFood();
        }
        for (String s : raw) {
            try {
                out.add(Material.valueOf(s.toUpperCase()));
            } catch (Exception e) {
                errors.log("Bad food material: " + s, null);
            }
        }
        if (out.isEmpty()) {
            for (String s : defaultFood()) out.add(Material.valueOf(s));
        }
        return out;
    }

    public List<Material> getTrashPool() {
        List<String> raw = plugin.getConfig().getStringList("captcha.trash-pool");
        List<Material> out = new ArrayList<Material>();
        if (raw == null || raw.isEmpty()) {
            raw = defaultTrash();
        }
        for (String s : raw) {
            try {
                out.add(Material.valueOf(s.toUpperCase()));
            } catch (Exception e) {
                errors.log("Bad trash material: " + s, null);
            }
        }
        if (out.isEmpty()) {
            for (String s : defaultTrash()) out.add(Material.valueOf(s));
        }
        return out;
    }

    private List<String> defaultFood() {
        List<String> l = new ArrayList<String>();
        l.add("BREAD"); l.add("CARROT"); l.add("POTATO"); l.add("BAKED_POTATO");
        l.add("APPLE"); l.add("COOKED_CHICKEN"); l.add("COOKIE"); l.add("MELON_SLICE");
        return l;
    }

    private List<String> defaultTrash() {
        List<String> l = new ArrayList<String>();
        l.add("DIRT"); l.add("COBBLESTONE"); l.add("STICK"); l.add("BONE");
        l.add("STRING"); l.add("PAPER"); l.add("LEATHER"); l.add("ARROW");
        return l;
    }

    public int getFoodTypes() { return plugin.getConfig().getInt("captcha.food-types", 3); }
    public int getFoodMin() { return plugin.getConfig().getInt("captcha.food-min-amount", 1); }
    public int getFoodMax() { return plugin.getConfig().getInt("captcha.food-max-amount", 4); }
    public int getTrashMin() { return plugin.getConfig().getInt("captcha.trash-min", 8); }
    public int getTrashMax() { return plugin.getConfig().getInt("captcha.trash-max", 12); }

    public String getMode() {
        return plugin.getConfig().getString("mode", "PROXY").toUpperCase();
    }

    public Location getLocalSpawn() {
        String wName = plugin.getConfig().getString("local-spawn.world", "world");
        World w = Bukkit.getWorld(wName);
        if (w == null) w = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0);
        double x = plugin.getConfig().getDouble("local-spawn.x", 0.5);
        double y = plugin.getConfig().getDouble("local-spawn.y", 100.0);
        double z = plugin.getConfig().getDouble("local-spawn.z", 0.5);
        float yaw = (float) plugin.getConfig().getDouble("local-spawn.yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble("local-spawn.pitch", 0.0);
        return new Location(w, x, y, z, yaw, pitch);
    }

    public String getProxyServer() {
        return plugin.getConfig().getString("proxy.server", "lobby");
    }

    /** Действие для ошибки: KICK по умолчанию. */
    public String getAction(String errorKey) {
        return plugin.getConfig().getString("actions." + errorKey, "KICK").toUpperCase();
    }
}

package ru.happyfilter.captcha.model;

import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Material;

/**
 * Модель одной капча-сессии (активный игрок в комнате).
 */
public class CaptchaSession {

    private final UUID playerId;
    private final String sessionId;
    private final Map<Material, Integer> expectedFood = new HashMap<Material, Integer>();
    private long deadlineMillis;
    private int timeLeft;
    private BukkitTask countdownTask;

    // Сохранённое состояние игрока (для LOCAL-режима и защиты от вайпа)
    private ItemStack[] savedContents;
    private ItemStack[] savedArmor;
    private ItemStack savedOffHand;
    private int savedLevel;
    private float savedExp;
    private GameMode savedGameMode;
    private int savedFood;
    private double savedHealth;

    public CaptchaSession(UUID playerId) {
        this.playerId = playerId;
        this.sessionId = UUID.randomUUID().toString();
    }

    public UUID getPlayerId() { return playerId; }
    public String getSessionId() { return sessionId; }
    public Map<Material, Integer> getExpectedFood() { return expectedFood; }

    public long getDeadlineMillis() { return deadlineMillis; }
    public void setDeadlineMillis(long v) { this.deadlineMillis = v; }

    public int getTimeLeft() { return timeLeft; }
    public void setTimeLeft(int v) { this.timeLeft = v; }

    public BukkitTask getCountdownTask() { return countdownTask; }
    public void setCountdownTask(BukkitTask t) { this.countdownTask = t; }

    public void cancelTask() {
        if (countdownTask != null) {
            try { countdownTask.cancel(); } catch (Exception ignored) {}
            countdownTask = null;
        }
    }

    public ItemStack[] getSavedContents() { return savedContents; }
    public void setSavedContents(ItemStack[] v) { this.savedContents = v; }
    public ItemStack[] getSavedArmor() { return savedArmor; }
    public void setSavedArmor(ItemStack[] v) { this.savedArmor = v; }
    public ItemStack getSavedOffHand() { return savedOffHand; }
    public void setSavedOffHand(ItemStack v) { this.savedOffHand = v; }
    public int getSavedLevel() { return savedLevel; }
    public void setSavedLevel(int v) { this.savedLevel = v; }
    public float getSavedExp() { return savedExp; }
    public void setSavedExp(float v) { this.savedExp = v; }
    public GameMode getSavedGameMode() { return savedGameMode; }
    public void setSavedGameMode(GameMode v) { this.savedGameMode = v; }
    public int getSavedFood() { return savedFood; }
    public void setSavedFood(int v) { this.savedFood = v; }
    public double getSavedHealth() { return savedHealth; }
    public void setSavedHealth(double v) { this.savedHealth = v; }
}

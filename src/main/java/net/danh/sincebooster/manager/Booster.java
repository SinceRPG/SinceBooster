package net.danh.sincebooster.manager;

import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Booster {
    private final String id;
    private final double multiplier;
    private final String profession;
    private final boolean permanent;
    private final Set<UUID> sharedPlayers = ConcurrentHashMap.newKeySet();
    private long endTime;

    public Booster(String id, double multiplier, long durationSeconds, @Nullable String profession, boolean permanent) {
        this.id = id;
        this.multiplier = multiplier;
        this.profession = profession;
        this.permanent = permanent;
        if (permanent) this.endTime = -1;
        else this.endTime = System.currentTimeMillis() + (durationSeconds * 1000);
    }

    public Booster(String id, double multiplier, long endTime, @Nullable String profession, boolean permanent, boolean isLoad) {
        this.id = id;
        this.multiplier = multiplier;
        this.endTime = endTime;
        this.profession = profession;
        this.permanent = permanent;
    }

    // [NEW] Giảm thời gian (Dùng cho tính năng Share trôi nhanh)
    // amountMillis: Số mili-giây bị trừ thêm
    public void reduceTime(long amountMillis) {
        if (permanent) return;
        this.endTime -= amountMillis;
    }

    public Set<UUID> getSharedPlayers() {
        return sharedPlayers;
    }

    public void addSharedPlayer(UUID uuid) {
        sharedPlayers.add(uuid);
    }

    public void removeSharedPlayer(UUID uuid) {
        sharedPlayers.remove(uuid);
    }

    // ... (Các getter và logic cũ giữ nguyên)
    public boolean isValid() {
        return permanent || System.currentTimeMillis() < endTime;
    }

    public void addTime(long seconds) {
        if (permanent) return;
        if (!isValid()) this.endTime = System.currentTimeMillis() + (seconds * 1000);
        else this.endTime += (seconds * 1000);
    }

    public boolean appliesTo(@Nullable String targetProfession) {
        if (this.profession == null) return true;
        if (targetProfession == null) return false;
        return this.profession.equalsIgnoreCase(targetProfession);
    }

    public double getMultiplier() {
        return multiplier;
    }

    public String getId() {
        return id;
    }

    public long getEndTime() {
        return endTime;
    }

    public String getProfession() {
        return profession;
    }

    public boolean isPermanent() {
        return permanent;
    }
}
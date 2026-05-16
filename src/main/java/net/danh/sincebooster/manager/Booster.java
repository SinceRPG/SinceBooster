package net.danh.sincebooster.manager;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Data model representing a single active booster.
 */
public class Booster {
    private final String id;
    private final double multiplier;
    private final String profession;
    private final boolean permanent;
    private final Set<UUID> sharedPlayers = ConcurrentHashMap.newKeySet();
    private UUID ownerUUID;
    private long endTime;
    private double cachedOfflineRate;

    // Constructor 1: Created newly in-game
    public Booster(String id, double multiplier, long durationSeconds, @Nullable String profession, boolean permanent, UUID ownerUUID) {
        this.id = id;
        this.multiplier = multiplier;
        this.profession = profession;
        this.permanent = permanent;
        this.ownerUUID = ownerUUID;
        this.endTime = permanent ? -1 : System.currentTimeMillis() + (durationSeconds * 1000);
        this.cachedOfflineRate = -1.0;
    }

    // Constructor 2: Loaded from DB
    public Booster(String id, double multiplier, long endTime, @Nullable String profession, boolean permanent, boolean isLoad, UUID ownerUUID, double cachedOfflineRate) {
        this.id = id;
        this.multiplier = multiplier;
        this.endTime = endTime;
        this.profession = profession;
        this.permanent = permanent;
        this.ownerUUID = ownerUUID;
        this.cachedOfflineRate = cachedOfflineRate;
    }

    public double getCachedOfflineRate() {
        return cachedOfflineRate;
    }

    public void setCachedOfflineRate(double rate) {
        this.cachedOfflineRate = rate;
    }

    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public void setOwnerUUID(UUID ownerUUID) {
        this.ownerUUID = ownerUUID;
    }

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

    public boolean isValid() {
        return permanent || System.currentTimeMillis() < endTime;
    }

    public void addTime(long seconds) {
        if (permanent) return;
        if (!isValid()) this.endTime = System.currentTimeMillis() + (seconds * 1000);
        else this.endTime += (seconds * 1000);
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Booster booster = (Booster) o;
        return Objects.equals(id, booster.id) &&
                Objects.equals(profession, booster.profession) &&
                Objects.equals(ownerUUID, booster.ownerUUID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, profession, ownerUUID);
    }
}

package net.coreprotect.services;
import java.util.UUID;
import net.coreprotect.config.CoreProtectConfig;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.consumer.ContainerLogEntry;
import net.coreprotect.consumer.LogEntry;
import net.coreprotect.database.CoreProtectDatabase;
import net.coreprotect.model.entity.EntityContainerTransaction;
import net.coreprotect.model.entity.EntityInteraction;
import net.coreprotect.model.entity.EntityLocation;
import net.coreprotect.model.entity.EntitySpawnData;
public final class CoreProtectRuntime {
    private volatile Consumer consumer;
    public Consumer consumer() { return null; }
    public CoreProtectDatabase database() { return null; }
    public CoreProtectConfig config() { return null; }
    public void log(LogEntry entry) {}
    public void logContainer(ContainerLogEntry entry) {}
    public void logEntitySpawn(String actor, EntitySpawnData data) {}
    public void updateEntityLocation(EntitySpawnData data) {}
    public void logTrackedEntityKill(LogEntry entry, UUID uuid, EntityLocation location) {}
    public void logEntityContainer(String actor, EntityContainerTransaction transaction) {}
    public boolean logEntityInteraction(String actor, EntityInteraction interaction) { return false; }
}
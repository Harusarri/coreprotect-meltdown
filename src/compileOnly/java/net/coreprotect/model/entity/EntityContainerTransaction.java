package net.coreprotect.model.entity;
import java.util.List;
public final class EntityContainerTransaction {
    public record ItemSnapshot(String itemType, String itemData) {}
    public List<ItemSnapshot> getOldContents() { return List.of(); }
    public List<ItemSnapshot> getNewContents() { return List.of(); }
    public EntityLocation getCurrentLocation() { return null; }
}
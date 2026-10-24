package net.coreprotect.platform;
public interface PlatformSender {
    String name();
    String identity();
    String worldName();
    PlatformLocation location();
    boolean isPlayer();
    boolean isCommandBlock();
    boolean hasPermission(String permission);
    default boolean hasPermission(String permission, boolean defaultValue) { return hasPermission(permission); }
    default boolean teleport(String world, double x, double y, double z) { return false; }
    void sendMessage(String text);
    void sendComponent(String text);
}
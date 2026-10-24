package net.coreprotect.command;
import net.coreprotect.platform.PlatformSender;
public final class CommandThrottle {
    public static boolean beginLookup(PlatformSender sender, long cooldownMillis) { return false; }
    public static void finishLookup(PlatformSender sender) {}
}
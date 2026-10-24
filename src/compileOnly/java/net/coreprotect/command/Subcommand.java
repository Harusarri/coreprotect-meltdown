package net.coreprotect.command;
import java.util.List;
import net.coreprotect.platform.PlatformSender;
public interface Subcommand {
    String name();
    String permission();
    boolean canUse(PlatformSender sender, List<String> args);
    void execute(PlatformSender sender, String label, List<String> args);
    List<String> tabComplete(PlatformSender sender, String label, List<String> args);
}
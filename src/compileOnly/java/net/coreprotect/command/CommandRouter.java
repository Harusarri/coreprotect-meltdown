package net.coreprotect.command;
import java.util.List;
import net.coreprotect.platform.PlatformSender;
public final class CommandRouter {
    public void register(Subcommand subcommand) {}
    public List<String> tabComplete(PlatformSender sender, List<String> args) { return List.of(); }
}
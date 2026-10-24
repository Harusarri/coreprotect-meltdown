package kr.harusarri.coreprotectmeltdown.mixin;

import java.util.*;
import net.coreprotect.command.CommandRouter;
import net.coreprotect.platform.PlatformSender;
import kr.harusarri.coreprotectmeltdown.DebugCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CommandRouter.class, remap = false)
public abstract class CommandRouterMixin {
    @Inject(method = "tabComplete", at = @At("RETURN"), cancellable = true, require = 1)
    private void guard$suggest(PlatformSender sender, List<String> args, CallbackInfoReturnable<List<String>> cir) {
        if (args.size() <= 1 && DebugCommand.permitted(sender)
                && "debug".startsWith(args.isEmpty() ? "" : args.getFirst().toLowerCase(Locale.ROOT))) {
            List<String> result = new ArrayList<>(cir.getReturnValue());
            if (!result.contains("debug")) result.add("debug");
            Collections.sort(result); cir.setReturnValue(result);
        }
    }
}

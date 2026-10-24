package kr.harusarri.coreprotectmeltdown.mixin;

import net.coreprotect.command.*;
import kr.harusarri.coreprotectmeltdown.DebugCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = CoreProtectCommandBootstrap.class, remap = false)
public abstract class CommandBootstrapMixin {
    @Inject(method = "createRouter", at = @At("RETURN"), require = 1)
    private static void guard$register(CallbackInfoReturnable<CommandRouter> cir) {
        cir.getReturnValue().register(new DebugCommand());
    }
}

package kr.harusarri.coreprotectmeltdown.test.mixin;
import kr.harusarri.coreprotectmeltdown.test.Probe;

import net.coreprotect.command.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=CoreProtectCommandBootstrap.class,remap=false)
public abstract class BootstrapMixin {
    @Inject(method="createRouter",at=@At("RETURN"),require=1)
    private static void register(CallbackInfoReturnable<CommandRouter> cir) {
        cir.getReturnValue().register(new Probe());
        cir.getReturnValue().register(new kr.harusarri.coreprotectmeltdown.test.StarvationProbe());
        cir.getReturnValue().register(new kr.harusarri.coreprotectmeltdown.test.LookupProbe());
        cir.getReturnValue().register(new kr.harusarri.coreprotectmeltdown.test.CompatibilityProbe());
    }
}

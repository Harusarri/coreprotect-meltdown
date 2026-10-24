package kr.harusarri.coreprotectmeltdown.mixin;
import net.coreprotect.platform.PlatformSender;
import net.coreprotect.network.PluginChannelHandshake;
import kr.harusarri.coreprotectmeltdown.LookupRunner.BufferedSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value = PluginChannelHandshake.class, remap = false)
public abstract class LookupHandshakeMixin {
    @Inject(method = "isPluginChannelPlayer(Lnet/coreprotect/platform/PlatformSender;)Z", at = @At("HEAD"), cancellable = true, require = 1)
    private static void meltdown$snapshot(PlatformSender sender, CallbackInfoReturnable<Boolean> cir) {
        if (sender instanceof BufferedSender buffered) cir.setReturnValue(buffered.pluginChannel);
    }
}

package kr.harusarri.coreprotectmeltdown.mixin;
import net.coreprotect.platform.PlatformSender;
import net.coreprotect.network.PluginChannelNetworking;
import kr.harusarri.coreprotectmeltdown.LookupRunner.BufferedSender;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value = PluginChannelNetworking.class, remap = false)
public abstract class LookupNetworkMixin {
    @WrapMethod(method = "send", require = 1)
    private static void meltdown$send(PlatformSender sender, byte[] payload, Operation<Void> original) {
        if (sender instanceof BufferedSender buffered) buffered.defer(() -> original.call(buffered.original, payload), payload.length);
        else original.call(sender, payload);
    }
}

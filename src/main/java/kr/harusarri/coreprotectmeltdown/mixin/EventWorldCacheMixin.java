package kr.harusarri.coreprotectmeltdown.mixin;
import java.util.Locale;
import net.coreprotect.services.CoreProtectRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Placement cache is optional; the actual log entry is already admitted independently. */
@Mixin(targets = "net.coreprotect.event.FabricEventSupport", remap = false)
public abstract class EventWorldCacheMixin {
    @Inject(method = "worldId", at = @At("HEAD"), cancellable = true, require = 1)
    private static void meltdown$cachedWorld(CoreProtectRuntime runtime, String world, CallbackInfoReturnable<Integer> cir) {
        int id = 0;
        if (runtime != null && runtime.database() != null && world != null && !world.isBlank()) {
            String prefix = runtime.config() == null ? "" : runtime.config().tablePrefixEffective();
            id = WorldCacheAccessor.meltdown$worlds().getOrDefault(prefix + "world|world|" + world.trim().toLowerCase(Locale.ROOT), 0);
        }
        cir.setReturnValue(id);
    }
}

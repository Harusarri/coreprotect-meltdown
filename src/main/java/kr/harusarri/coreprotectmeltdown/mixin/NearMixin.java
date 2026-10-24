package kr.harusarri.coreprotectmeltdown.mixin;
import java.util.*;
import net.coreprotect.command.NearSubcommand;
import net.coreprotect.platform.PlatformSender;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value = NearSubcommand.class, remap = false)
public abstract class NearMixin {
    @WrapMethod(method = "execute", require = 1)
    private void meltdown$recent(PlatformSender sender, String label, List<String> args, Operation<Void> original) {
        if (args != null && !args.isEmpty()) {
            boolean hasExplicitRadius = args.stream().anyMatch(a -> {
                if (a == null) return false;
                String lower = a.trim().toLowerCase(Locale.ROOT);
                return lower.startsWith("r:") || lower.startsWith("radius:");
            });
            if (!hasExplicitRadius) {
                List<String> normalized = new ArrayList<>(args.size());
                boolean radiusConverted = false;
                for (String raw : args) {
                    String trimmed = raw == null ? "" : raw.trim();
                    if (!radiusConverted && trimmed.matches("^\\d+(?:\\.\\d+)?(?:x\\d+(?:\\.\\d+)?){0,2}$")) {
                        normalized.add("r:" + trimmed);
                        radiusConverted = true;
                    } else {
                        normalized.add(raw);
                    }
                }
                args = normalized;
            }
        }
        original.call(sender, label, args);
    }
}

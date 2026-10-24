package kr.harusarri.coreprotectmeltdown.mixin;

import java.util.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.coreprotect.lookup.LookupParser", remap = false)
public abstract class LookupParserMixin {
    @ModifyVariable(method = "parse", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static List<String> meltdown$normalizeLookupArgs(List<String> args) {
        if (args == null || args.size() <= 1) {
            return args;
        }
        boolean hasExplicitTime = args.stream().anyMatch(a -> {
            if (a == null) return false;
            String lower = a.trim().toLowerCase(Locale.ROOT);
            return lower.startsWith("t:") || lower.startsWith("time:");
        });
        if (hasExplicitTime) {
            return args;
        }
        List<String> normalized = new ArrayList<>(args.size());
        boolean timeConverted = false;
        for (String raw : args) {
            String trimmed = raw == null ? "" : raw.trim();
            if (!timeConverted && trimmed.matches("(?i)^\\d+(?:\\.\\d+)?(?:s|m|h|d|w|mo|y)(?:\\d+(?:\\.\\d+)?(?:s|m|h|d|w|mo|y))*$")) {
                normalized.add("t:" + trimmed);
                timeConverted = true;
            } else {
                normalized.add(raw);
            }
        }
        return timeConverted ? normalized : args;
    }

    @Redirect(
        method = "parse",
        at = @At(
            value = "INVOKE",
            target = "Lnet/coreprotect/lookup/LookupParser;isHistoryLookup(Ljava/util/Set;)Z",
            ordinal = 1
        ),
        require = 1
    )
    private static boolean meltdown$allowLookupWithoutTime(Set<String> actions) {
        return true;
    }

    @Inject(method = "isUnscopedHistoryLookup", at = @At("HEAD"), cancellable = true, require = 1)
    private static void meltdown$unscopedWithoutRadius(
            Set<String> actions,
            int radius,
            boolean worldScopeSpecified,
            @Coerce Object worldEditSelection,
            CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(radius < 0 && !worldScopeSpecified && worldEditSelection == null);
    }
}

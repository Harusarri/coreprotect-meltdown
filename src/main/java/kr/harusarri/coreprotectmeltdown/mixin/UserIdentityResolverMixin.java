package kr.harusarri.coreprotectmeltdown.mixin;

import java.util.*;
import kr.harusarri.coreprotectmeltdown.LookupRunner;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.Query;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.coreprotect.database.UserIdentityResolver", remap = false)
public abstract class UserIdentityResolverMixin {

    @Inject(method = "playerExists", at = @At("RETURN"), cancellable = true, require = 1)
    private static void meltdown$fallbackUsernameLogExists(Handle handle, String prefix, String token, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) {
            return;
        }
        if (token == null || token.isBlank() || "#global".equalsIgnoreCase(token.trim())) {
            return;
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        boolean prevFailed = LookupRunner.saveFailureState();
        try {
            Optional<Integer> found = ((Query) handle.createQuery("SELECT 1 FROM " + prefix + "username_log WHERE LOWER(user) = :user LIMIT 1")
                    .bind("user", normalized))
                    .mapTo(Integer.class)
                    .findFirst();
            if (found.isPresent()) {
                cir.setReturnValue(true);
            }
        } catch (Exception ignored) {
            LookupRunner.restoreFailureState(prevFailed);
        }
    }

    @Inject(method = "resolveUserIds", at = @At("RETURN"), cancellable = true, require = 1)
    private static void meltdown$fallbackHistoricalUserIds(Handle handle, String prefix, Set<String> tokens, CallbackInfoReturnable<Set<Integer>> cir) {
        if (tokens == null || tokens.isEmpty()) {
            return;
        }
        Set<Integer> existing = cir.getReturnValue();
        LinkedHashSet<Integer> merged = existing == null ? new LinkedHashSet<>() : new LinkedHashSet<>(existing);

        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String token : tokens) {
            if (token == null || token.isBlank() || token.startsWith("#")) continue;
            names.add(token.trim().toLowerCase(Locale.ROOT));
        }
        if (names.isEmpty()) {
            return;
        }

        boolean prevFailed = LookupRunner.saveFailureState();
        try {
            List<String> uuids = ((Query) handle.createQuery(
                    "SELECT DISTINCT uuid FROM " + prefix + "username_log WHERE LOWER(user) IN (<names>) AND uuid IS NOT NULL AND uuid <> ''")
                    .bindList("names", names))
                    .mapTo(String.class)
                    .list();

            if (!uuids.isEmpty()) {
                Set<Integer> resolvedIds = ((Query) handle.createQuery(
                        "SELECT id FROM " + prefix + "user WHERE LOWER(uuid) IN (<uuids>)")
                        .bindList("uuids", uuids.stream().map(u -> u.toLowerCase(Locale.ROOT)).toList()))
                        .mapTo(Integer.class)
                        .set();
                if (!resolvedIds.isEmpty()) {
                    merged.addAll(resolvedIds);
                    cir.setReturnValue(Collections.unmodifiableSet(merged));
                }
            }
        } catch (Exception ignored) {
            LookupRunner.restoreFailureState(prevFailed);
        }
    }
}

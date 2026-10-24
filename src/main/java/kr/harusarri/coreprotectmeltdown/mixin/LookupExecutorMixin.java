package kr.harusarri.coreprotectmeltdown.mixin;

import java.util.Map;
import net.coreprotect.platform.PlatformSender;
import net.coreprotect.config.CoreProtectConfig;
import net.coreprotect.database.CoreProtectDatabase;
import kr.harusarri.coreprotectmeltdown.LookupRunner;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.Query;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.coreprotect.lookup.FabricLookupExecutor", remap = false)
public abstract class LookupExecutorMixin {
    @Redirect(method = "execute", at = @At(value = "INVOKE", target = "Lnet/coreprotect/lookup/LookupWorldSupport;worldLookupError(Lnet/coreprotect/database/CoreProtectDatabase;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"), require = 1)
    private String meltdown$deferWorldCheck(CoreProtectDatabase database, String prefix, String world) {
        return null; // The same validation runs inside LookupRunner, before the actual query.
    }

    @Inject(method = "executeLookupThrottled", at = @At("HEAD"), cancellable = true, require = 1)
    private void meltdown$lookup(PlatformSender sender, String label, CoreProtectConfig config, CoreProtectDatabase database,
            @Coerce Object request, boolean announce, long cooldownMillis, CallbackInfo ci) {
        LookupRunner.submit(this, sender, label, config, database, request, announce, cooldownMillis);
        ci.cancel();
    }

    @Inject(method = "resolveOnlineUserNames", at = @At("HEAD"), cancellable = true, require = 1)
    private static void meltdown$preserveLowercaseUsers(@Coerce Object request, CallbackInfoReturnable<Object> cir) {
        cir.setReturnValue(request);
    }

    @Inject(method = "appendSpatialFilter", at = @At("HEAD"), cancellable = true, require = 1)
    private static void meltdown$optimizeSpatialFilter(StringBuilder sql, Map<String, Object> binds,
            @Coerce Object request, String alias, CallbackInfo ci) {
        if (LookupRunner.appendOptimizedSpatialFilter(sql, binds, (LookupRequestAccessor) request, alias)) {
            ci.cancel();
        }
    }

    @ModifyVariable(method = "createCountQuery", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static String meltdown$optimizeCountSql(String sql, Handle handle, String origSql, @Coerce Object request) {
        return LookupRunner.optimizeCountSql(sql, (LookupRequestAccessor) request);
    }

    @Redirect(
        method = "lambda$queryUnionPage$1",
        at = @At(
            value = "INVOKE",
            target = "Lorg/jdbi/v3/core/Handle;createQuery(Ljava/lang/String;)Lorg/jdbi/v3/core/statement/Query;",
            ordinal = 0
        ),
        require = 1
    )
    private static Query meltdown$createUnionPageQuery(Handle handle, String sql) {
        return handle.createQuery(LookupRunner.optimizeUnionPageSql(sql));
    }
}

package kr.harusarri.coreprotectmeltdown.mixin;
import java.sql.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import kr.harusarri.coreprotectmeltdown.LookupRunner;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets = "org.jdbi.v3.core.statement.SqlStatement", remap = false)
public abstract class LookupStatementMixin {
    @Shadow private PreparedStatement stmt;
    @Inject(method = "beforeExecution", at = @At("RETURN"), require = 1)
    private void meltdown$deadline(CallbackInfo ci) throws SQLException { LookupRunner.limitStatement(stmt); }
    @WrapMethod(method = "internalExecute", require = 1)
    private PreparedStatement meltdown$observeFailure(Operation<PreparedStatement> original) throws Exception {
        try { return original.call(); }
        catch (Exception e) { LookupRunner.statementFailed(); throw e; }
    }
}

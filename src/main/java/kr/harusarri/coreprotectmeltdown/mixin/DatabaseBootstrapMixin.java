package kr.harusarri.coreprotectmeltdown.mixin;

import java.util.List;
import java.util.logging.Logger;
import net.coreprotect.database.DatabaseType;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.Query;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.coreprotect.database.DatabaseBootstrap", remap = false)
public abstract class DatabaseBootstrapMixin {
    private static final Logger LOG = Logger.getLogger("Coreprotect-meltdown");

    @Inject(method = "initializeSchema(Lorg/jdbi/v3/core/Handle;Ljava/lang/String;Lnet/coreprotect/database/DatabaseType;)V", at = @At("TAIL"), require = 1)
    private static void meltdown$ensurePerformanceIndexes(Handle handle, String prefix, DatabaseType type, CallbackInfo ci) {
        if (type == null || type.isClickHouse() || type.isDuckDb()) {
            return;
        }
        try {
            if (type.isSqlite()) {
                for (String table : List.of("block", "container", "item", "session", "sign", "chat", "command", "entity_container", "entity_interaction")) {
                    safeExecute(handle, "CREATE INDEX IF NOT EXISTS idx_" + prefix + table + "_wid_xz_time ON " + prefix + table + " (wid, x, z, time)");
                }
                safeExecute(handle, "CREATE INDEX IF NOT EXISTS idx_" + prefix + "session_user_time ON " + prefix + "session (user, time)");
                safeExecute(handle, "CREATE INDEX IF NOT EXISTS idx_" + prefix + "session_time ON " + prefix + "session (time)");
                safeExecute(handle, "CREATE INDEX IF NOT EXISTS idx_" + prefix + "entity_spawn_spawn_block ON " + prefix + "entity_spawn (spawn_block_id)");
                safeExecute(handle, "CREATE INDEX IF NOT EXISTS idx_" + prefix + "username_log_user ON " + prefix + "username_log (user)");
                safeExecute(handle, "CREATE INDEX IF NOT EXISTS idx_" + prefix + "username_log_lower_user ON " + prefix + "username_log (LOWER(user))");
                safeExecute(handle, "CREATE INDEX IF NOT EXISTS idx_" + prefix + "user_lower_user ON " + prefix + "user (LOWER(user))");
                safeExecute(handle, "CREATE INDEX IF NOT EXISTS idx_" + prefix + "user_lower_uuid ON " + prefix + "user (LOWER(uuid))");
            } else if (type.isMysql()) {
                ensureMysqlIndex(handle, prefix + "entity_spawn", "idx_" + prefix + "entity_spawn_spawn_block", "(spawn_block_id)");
                ensureMysqlIndex(handle, prefix + "username_log", "idx_" + prefix + "username_log_user", "(user)");
                ensureMysqlIndex(handle, prefix + "session", "idx_" + prefix + "session_wid_xz_time", "(wid, x, z, time)");
                ensureMysqlIndex(handle, prefix + "session", "idx_" + prefix + "session_user_time", "(user, time)");
                ensureMysqlIndex(handle, prefix + "session", "idx_" + prefix + "session_time", "(time)");
            }
            LOG.info("[CPMeltdown] Verified and ensured spatial (wid,x,z,time), session, entity_spawn & username_log query acceleration indexes.");
        } catch (Exception e) {
            LOG.fine("[CPMeltdown] Performance index check notice: " + e.getMessage());
        }
    }

    private static void safeExecute(Handle handle, String sql) {
        try {
            handle.execute(sql);
        } catch (Exception ignored) {
        }
    }

    private static void ensureMysqlIndex(Handle handle, String tableName, String indexName, String columns) {
        try {
            Integer existing = ((Query) handle.createQuery(
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = :table AND index_name = :index")
                    .bind("table", tableName)
                    .bind("index", indexName))
                    .mapTo(Integer.class)
                    .findOne()
                    .orElse(0);
            if (existing == null || existing == 0) {
                handle.execute("CREATE INDEX " + indexName + " ON " + tableName + " " + columns);
            }
        } catch (Exception ignored) {
        }
    }
}

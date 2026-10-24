package kr.harusarri.coreprotectmeltdown;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.Logger;
import net.coreprotect.CoreProtect;
import net.coreprotect.command.CommandThrottle;
import net.coreprotect.config.CoreProtectConfig;
import net.coreprotect.database.CoreProtectDatabase;
import net.coreprotect.lookup.LookupWorldSupport;
import net.coreprotect.network.PluginChannelHandshake;
import net.coreprotect.permission.Permissions;
import net.coreprotect.platform.*;
import kr.harusarri.coreprotectmeltdown.mixin.LookupRequestAccessor;

/** One read-only lookup, no task queue; game access and result delivery stay on the server thread. */
public final class LookupRunner {
    private static final Logger LOG = Logger.getLogger("Coreprotect-meltdown");
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final AtomicLong COMPLETED = new AtomicLong(), REJECTED = new AtomicLong(), FAILED = new AtomicLong();
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> QUERY_FAILED = new ThreadLocal<>();
    private static final ThreadLocal<LookupRequestAccessor> CURRENT_REQUEST = new ThreadLocal<>();
    private static volatile long lastMillis;
    private static final ExecutorService WORKER = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        new SynchronousQueue<>(), r -> { Thread t = new Thread(r, "Coreprotect-meltdown-Lookup"); t.setDaemon(true); return t; });
    private static final Method EXECUTE = method("net.coreprotect.lookup.FabricLookupExecutor", "executeLookup",
        PlatformSender.class, String.class, CoreProtectConfig.class, CoreProtectDatabase.class, type("net.coreprotect.lookup.LookupRequest"), boolean.class);
    private static final Method HANDSHAKE = method("net.coreprotect.network.PluginChannelHandshake", "isPluginChannelPlayer", PlatformSender.class);
    private static Class<?> type(String name) { try { return Class.forName(name); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    private static Method method(String owner, String name, Class<?>... parameters) {
        try { Method method = type(owner).getDeclaredMethod(name, parameters); method.setAccessible(true); return method; }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    public static Executor serverExecutor() {
        try { return (Executor)CoreProtect.class.getMethod("server").invoke(CoreProtect.getInstance()); }
        catch (Exception e) { throw new IllegalStateException("CoreProtect server executor unavailable", e); }
    }
    private static final String PREFIX = "§8[§3CP§bMeltdown§8] §r";
    public static boolean isActive() { return ACTIVE.get(); }
    public static long completedCount() { return COMPLETED.get(); }
    public static long failedCount() { return FAILED.get(); }
    public static long rejectedCount() { return REJECTED.get(); }
    public static long lastMillis() { return lastMillis; }
    public static void submit(Object executor, PlatformSender sender, String label, CoreProtectConfig config,
            CoreProtectDatabase database, Object request, boolean announce, long cooldownMillis) {
        String lang = MeltdownI18n.resolveLang(sender);
        if (!ACTIVE.compareAndSet(false, true)) { REJECTED.incrementAndGet(); sender.sendMessage(PREFIX + MeltdownI18n.getByLang(lang, "lookup.busy")); return; }
        if (!CommandThrottle.beginLookup(sender, cooldownMillis)) {
            ACTIVE.set(false); REJECTED.incrementAndGet(); sender.sendMessage(PREFIX + MeltdownI18n.getByLang(lang, "lookup.cooldown")); return;
        }
        try {
            Executor main = serverExecutor();
            BufferedSender buffered = new BufferedSender(sender, lang);
            int timeout = GuardService.LIMITER.config().lookupTimeoutSeconds();
            WORKER.execute(() -> {
                long start = System.nanoTime();
                DEADLINE.set(start + timeout * 1_000_000_000L);
                QUERY_FAILED.set(false);
                CURRENT_REQUEST.set((LookupRequestAccessor)request);
                try {
                    String world = ((LookupRequestAccessor)request).meltdown$world();
                    String error = LookupWorldSupport.worldLookupError(database, config.tablePrefixEffective(), world);
                    if (error != null) buffered.sendMessage(PREFIX + "§c" + error);
                    else EXECUTE.invoke(executor, buffered, label, config, database, request, announce);
                } catch (Exception e) {
                    QUERY_FAILED.set(true);
                    Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
                    LOG.warning("Lookup failed: " + EventInfo.safe(cause == null ? e.toString() : cause.toString()));
                    buffered.fail(PREFIX + MeltdownI18n.getByLang(lang, "lookup.aborted"));
                } finally {
                    if (Boolean.TRUE.equals(QUERY_FAILED.get())) {
                        FAILED.incrementAndGet();
                        buffered.fail(PREFIX + MeltdownI18n.getByLang(lang, "lookup.aborted"));
                    } else COMPLETED.incrementAndGet();
                    CURRENT_REQUEST.remove();
                    QUERY_FAILED.remove();
                    DEADLINE.remove();
                    lastMillis = (System.nanoTime() - start) / 1_000_000;
                    // Keep ACTIVE until delivery completes; even a stalled server cannot collect result tasks.
                    try { main.execute(() -> {
                        try { buffered.flush(); }
                        catch (RuntimeException e) { LOG.warning("Lookup response failed: " + EventInfo.safe(e.toString())); }
                        finally { CommandThrottle.finishLookup(sender); ACTIVE.set(false); }
                    }); } catch (RuntimeException e) { CommandThrottle.finishLookup(sender); ACTIVE.set(false); }
                }
            });
        } catch (RuntimeException e) {
            CommandThrottle.finishLookup(sender); ACTIVE.set(false); FAILED.incrementAndGet();
            sender.sendMessage(PREFIX + MeltdownI18n.getByLang(lang, "lookup.start_failed"));
            LOG.warning(EventInfo.safe(e.toString()));
        }
    }
    /** Applied after Jdbi customizers, only for this addon's bounded lookup worker. */
    public static void limitStatement(PreparedStatement statement) throws SQLException {
        Long deadline = DEADLINE.get();
        if (deadline == null) return;
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SQLTimeoutException("Coreprotect-meltdown lookup time budget exceeded");
        int seconds = (int)Math.max(1, (remaining + 999_999_999L) / 1_000_000_000L);
        int current = statement.getQueryTimeout();
        statement.setQueryTimeout(current > 0 ? Math.min(current, seconds) : seconds);
    }
    public static void statementFailed() { if (DEADLINE.get() != null) QUERY_FAILED.set(true); }
    public static boolean saveFailureState() { return Boolean.TRUE.equals(QUERY_FAILED.get()); }
    public static void restoreFailureState(boolean previous) { if (DEADLINE.get() != null) QUERY_FAILED.set(previous); }
    public static boolean appendOptimizedSpatialFilter(StringBuilder sql, Map<String, Object> binds, LookupRequestAccessor request, String alias) {
        if (request == null || request.meltdown$worldEditSelection() != null || request.meltdown$radius() < 0 || request.meltdown$origin() == null) {
            return false;
        }
        PlatformLocation origin = request.meltdown$origin();
        int minX = origin.x() - request.meltdown$radiusX();
        int maxX = origin.x() + request.meltdown$radiusX();
        if (maxX < minX || (long)maxX - (long)minX > 64L) {
            return false;
        }
        String qualifier = alias == null || alias.isBlank() ? "" : alias + ".";
        int minZ = origin.z() - request.meltdown$radiusZ();
        int maxZ = origin.z() + request.meltdown$radiusZ();
        if (minX == maxX) {
            sql.append(" AND ").append(qualifier).append("x = :minX AND ").append(qualifier).append("x = :maxX");
        } else {
            sql.append(" AND ").append(qualifier).append("x IN (:minX");
            for (int x = minX + 1; x < maxX; x++) {
                sql.append(',').append(x);
            }
            sql.append(",:maxX)");
        }
        sql.append(" AND ").append(qualifier).append("z BETWEEN :minZ AND :maxZ");
        binds.put("minX", minX);
        binds.put("maxX", maxX);
        binds.put("minZ", minZ);
        binds.put("maxZ", maxZ);
        Integer radiusY = request.meltdown$radiusY();
        if (radiusY != null) {
            sql.append(" AND ").append(qualifier).append("y BETWEEN :minY AND :maxY");
            binds.put("minY", origin.y() - radiusY);
            binds.put("maxY", origin.y() + radiusY);
        }
        return true;
    }
    public static String optimizeEntityOr(String sql, LookupRequestAccessor request) {
        if (sql == null) return null;
        String alias = sql.contains("entity_spawn es ON ec.entity_spawn_id") ? "ec"
            : (sql.contains("entity_spawn es ON ei.entity_spawn_id") ? "ei" : null);
        if (alias == null) return sql;
        int joinIdx = sql.indexOf("INNER JOIN ");
        int esIdx = sql.indexOf("entity_spawn es ON " + alias + ".entity_spawn_id");
        String prefix = (joinIdx >= 0 && esIdx > joinIdx + 11) ? sql.substring(joinIdx + 11, esIdx) : "co_";
        if (request != null && request.meltdown$worldEditSelection() == null && request.meltdown$radius() >= 0 && request.meltdown$origin() != null) {
            int minX = request.meltdown$origin().x() - request.meltdown$radiusX();
            int maxX = request.meltdown$origin().x() + request.meltdown$radiusX();
            if (maxX >= minX && (long)maxX - (long)minX <= 64L) {
                StringBuilder xIn = new StringBuilder(alias).append(".x IN (");
                for (int x = minX; x <= maxX; x++) {
                    if (x > minX) xIn.append(',');
                    xIn.append(x);
                }
                xIn.append(')');
                sql = sql.replace(alias + ".x >= :minX AND " + alias + ".x < :maxXExclusive", xIn.toString());
            }
        }
        int orIdx = sql.indexOf(" OR (es.current_wid");
        if (orIdx < 0) orIdx = sql.indexOf(" OR ((es.current_wid");
        if (orIdx >= 0) {
            int openParen = orIdx + 4;
            int depth = 0;
            int closeParen = -1;
            for (int i = openParen; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) { closeParen = i; break; }
                }
            }
            if (closeParen > openParen) {
                String inner = sql.substring(openParen + 1, closeParen);
                String replacement = alias + ".entity_spawn_id IN (SELECT es_loc.id FROM " + prefix + "entity_spawn es_loc WHERE " + inner.replace("es.", "es_loc.") + ")";
                sql = sql.substring(0, openParen + 1) + replacement + sql.substring(closeParen);
                sql = sql.replace(" OR es.id IN (", " OR " + alias + ".entity_spawn_id IN (");
            }
        }
        return sql;
    }
    public static String optimizeCountSql(String sql, LookupRequestAccessor request) {
        if (sql == null) return null;
        sql = optimizeEntityOr(sql, request);
        if (request != null && !request.meltdown$countOnly() && !request.meltdown$summary() && sql.startsWith("SELECT COUNT(*) FROM ")) {
            long page = Math.max(1, request.meltdown$page());
            long rowsPerPage = Math.max(10, request.meltdown$rowsPerPage());
            long cap = Math.min(25000L, Math.max(400L, page * rowsPerPage + 400L));
            String rest = sql.substring("SELECT COUNT(*) FROM ".length());
            return "SELECT COUNT(*) FROM (SELECT 1 FROM " + rest + " LIMIT " + cap + ") meltdown_cnt";
        }
        return sql;
    }
    public static String optimizeUnionPageSql(String sql) {
        String prefix = "SELECT * FROM (";
        String suffix = ") lookup_rows ORDER BY logged_at_utc DESC, source_order DESC, row_id DESC LIMIT :limit OFFSET :offset";
        if (sql == null || !sql.startsWith(prefix) || !sql.endsWith(suffix)) {
            return sql;
        }
        LookupRequestAccessor request = CURRENT_REQUEST.get();
        long branchCap = 100L;
        if (request != null) {
            long page = Math.max(1, request.meltdown$page());
            long rowsPerPage = Math.max(10, request.meltdown$rowsPerPage());
            branchCap = Math.min(25000L, Math.max(20L, page * rowsPerPage));
        }
        String unionSql = sql.substring(prefix.length(), sql.length() - suffix.length());
        String[] branches = unionSql.split(" UNION ALL ");
        List<String> wrapped = new ArrayList<>(branches.length);
        for (int i = 0; i < branches.length; i++) {
            String b = optimizeEntityOr(branches[i], request);
            String orderClause = "logged_at_utc DESC, row_id DESC";
            int soIdx = b.indexOf("AS source_order, ");
            int ridIdx = b.indexOf(" AS row_id, ");
            int timeIdx = b.indexOf(" * 1000 AS logged_at_utc");
            if (soIdx >= 0 && ridIdx > soIdx + 17 && timeIdx > ridIdx + 12) {
                String idCol = b.substring(soIdx + 17, ridIdx).trim();
                String timeCol = b.substring(ridIdx + 12, timeIdx).trim();
                if (!idCol.isEmpty() && !timeCol.isEmpty()) {
                    orderClause = timeCol + " DESC, " + idCol + " DESC";
                }
            }
            wrapped.add("SELECT * FROM (" + b + " ORDER BY " + orderClause + " LIMIT " + branchCap + ") meltdown_b" + i);
        }
        return prefix + String.join(" UNION ALL ", wrapped) + suffix;
    }
    public static String status() {
        return "lookupActive=" + ACTIVE.get() + " completed=" + COMPLETED.get() + " failed=" + FAILED.get()
            + " rejected=" + REJECTED.get() + " lastMillis=" + lastMillis;
    }
    public static final class BufferedSender implements PlatformSender {
        public final PlatformSender original;
        public final boolean pluginChannel;
        private final String name, identity, world, lang;
        private final boolean player, commandBlock, canGive;
        private final PlatformLocation location;
        private final List<Runnable> output = new ArrayList<>();
        private int bytes;
        private boolean truncated;
        BufferedSender(PlatformSender sender) {
            this(sender, MeltdownI18n.resolveLang(sender));
        }
        BufferedSender(PlatformSender sender, String lang) {
            original = sender; name = sender.name(); identity = sender.identity(); world = sender.worldName();
            this.lang = lang != null ? lang : MeltdownI18n.EN_US;
            player = sender.isPlayer(); commandBlock = sender.isCommandBlock(); location = sender.location();
            // CoreProtect 1.3 checks this while rendering item-recovery links on our worker.
            // Resolve permissions on the server thread, using the same default as CoreProtect.
            canGive = player && Permissions.has(sender, "coreprotect.give", false);
            try { pluginChannel = (Boolean)HANDSHAKE.invoke(null, sender); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        public String name() { return name; }
        public String identity() { return identity; }
        public String worldName() { return world; }
        public PlatformLocation location() { return location; }
        public boolean isPlayer() { return player; }
        public boolean isCommandBlock() { return commandBlock; }
        // Other lookup permissions and world selection were resolved before dispatch.
        public boolean hasPermission(String permission) { return "coreprotect.give".equals(permission) && canGive; }
        public void sendMessage(String text) { message(text, false); }
        public void sendComponent(String text) { message(text, true); }
        private void message(String text, boolean component) {
            if (text == null) return;
            if (text.length() > 16384) { truncated = true; return; }
            defer(() -> { if (component) original.sendComponent(text); else original.sendMessage(text); }, text.length() * 2);
        }
        public void defer(Runnable send, int size) {
            if (output.size() >= 256 || size > 262144 - bytes) { truncated = true; return; }
            bytes += size; output.add(send);
        }
        void fail(String message) { output.clear(); bytes = 0; truncated = false; sendMessage(message); }
        void flush() {
            for (Runnable send : output) send.run();
            if (truncated) original.sendMessage(PREFIX + MeltdownI18n.getByLang(lang, "lookup.truncated"));
        }
    }
}

package kr.harusarri.coreprotectmeltdown;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.coreprotect.CoreProtect;
import net.coreprotect.consumer.*;
import net.coreprotect.model.entity.*;
import net.fabricmc.loader.api.FabricLoader;

public final class GuardService {
    private static final Logger LOG = Logger.getLogger("CoreProtectMeltdown");
    public static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir().resolve("coreprotect-meltdown");
    public static final Path CONFIG = DIRECTORY.resolve("config.properties");
    public static final Path REPORT = DIRECTORY.resolve("debug.txt");
    public static final Limiter LIMITER = new Limiter(loadInitial(), System::nanoTime);
    private static final AtomicBoolean STARTED = new AtomicBoolean(), WRITE_PENDING = new AtomicBoolean();
    private static final ScheduledExecutorService WRITER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CoreProtectMeltdown-Report"); t.setDaemon(true); return t;
    });
    private static volatile boolean heapPressure;
    private static volatile int heapPercent;
    private static volatile String lastWrite = "never", lastError = "none";
    private static long lastReport;
    private static int publications; // Protected by LIMITER; counts admitted calls not yet returned.
    private GuardService() {}
    private static GuardConfig loadInitial() {
        try {
            ConfigMigration.migrate(FabricLoader.getInstance().getConfigDir());
            return GuardConfig.load(CONFIG);
        }
        catch (IOException | RuntimeException e) { throw new IllegalStateException("Invalid CoreProtectMeltdown configuration: " + CONFIG, e); }
    }
    public static void start() {
        if (!STARTED.compareAndSet(false, true)) return;
        try {
            if (Files.exists(REPORT)) Files.move(REPORT, DIRECTORY.resolve("debug.previous.txt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { reportFailure(e); }
        WRITER.scheduleWithFixedDelay(GuardService::periodic, 1, 1, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            WRITER.shutdown();
            try { WRITER.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            writeReport();
        }, "CoreProtectMeltdown-Shutdown"));
        LOG.info("CoreProtectMeltdown active: Mixin runtime admission, /co debug, reports at " + REPORT);
    }
    private static void periodic() {
        try {
            Runtime runtime = Runtime.getRuntime();
            heapPercent = (int)((runtime.totalMemory() - runtime.freeMemory()) * 100 / runtime.maxMemory());
            GuardConfig c = LIMITER.config();
            if (heapPercent >= c.heapStopPercent()) heapPressure = true;
            else if (heapPercent <= c.heapResumePercent()) heapPressure = false;
            long now = System.nanoTime();
            if (now - lastReport >= c.reportSeconds() * 1_000_000_000L) {
                lastReport = now;
                writeReport();
                // Routine factory cuts stay in debug.txt and /co debug; no periodic console warning.
            }
        } catch (Exception e) { reportFailure(e); }
    }
    public static boolean enter(Consumer consumer, EventInfo info) {
        synchronized (LIMITER) {
            if (consumer != null && !LIMITER.allow(info, consumer.pending() + publications, heapPressure)) return false;
            publications++;
            return true;
        }
    }
    public static void leave() { synchronized (LIMITER) { publications--; } }
    public static boolean heapPressure() { return heapPressure; }
    public static int heapPercent() { return heapPercent; }
    public static String lastWrite() { return lastWrite; }
    public static String lastError() { return lastError; }
    public static int pending() {
        CoreProtect cp = CoreProtect.getInstance();
        return cp == null || cp.runtime() == null || cp.runtime().consumer() == null ? -1 : cp.runtime().consumer().pending();
    }
    public static boolean requestReport() {
        if (!WRITE_PENDING.compareAndSet(false, true)) return false;
        WRITER.execute(() -> { try { writeReport(); } finally { WRITE_PENDING.set(false); } });
        return true;
    }
    public static void reload() throws IOException { LIMITER.configure(GuardConfig.load(CONFIG)); }
    public static List<String> status() {
        return status(LIMITER.snapshot());
    }
    private static List<String> status(Limiter.Snapshot s) {
        return List.of("[CPMeltdown] enabled=" + s.config().enabled() + "; pending=" + pending() + " soft/hard=" + s.config().queueSoftLimit() + "/" + s.config().queueHardLimit()
                + " consumerPaused=" + Consumer.isPaused(),
            "[CPMeltdown] admitted=" + s.admitted() + " cut=" + s.cut() + " peakPending=" + s.peakPending()
                + " heap=" + heapPercent + "% circuit=" + heapPressure,
            "[CPMeltdown] reasons=" + s.reasons() + " heatmapCells=" + s.hotspots().size() + " unknownLocation=" + s.unlocatedCuts(),
            "[CPMeltdown] PLAYER_ATTRIBUTED " + s.categories().get(Limiter.Category.PLAYER_ATTRIBUTED).summary(),
            "[CPMeltdown] PLAYER_FACTORY " + s.categories().get(Limiter.Category.PLAYER_FACTORY).summary(),
            "[CPMeltdown] " + LookupRunner.status(),
            "[CPMeltdown] Dropped events are NOT stored and cannot be rolled back. Admitted does not mean committed to DB.",
            "[CPMeltdown] debug.txt=" + REPORT + " lastWrite=" + lastWrite + " error=" + lastError);
    }
    private static synchronized void writeReport() {
        try {
            Limiter.Snapshot s = LIMITER.snapshot();
            StringBuilder out = new StringBuilder(8192);
            out.append("Coreprotect-meltdown 1.3.1\nGenerated: ").append(Instant.now()).append("\nSince: ").append(s.since()).append('\n');
            out.append("Process-ID: ").append(s.processId()).append("\nUptime-seconds: ").append(s.uptimeSeconds())
                .append("\nConfig-revision: ").append(s.configRevision()).append("\nConfig-since: ").append(s.configuredAt()).append('\n');
            for (String line : status(s)) out.append(line).append('\n');
            out.append("Configuration: ").append(s.config()).append('\n');
            out.append("Factory subset: admitted=").append(s.factoryAdmitted()).append(" cut=").append(s.factoryCut()).append(" activeCells=").append(s.factoryCells()).append(" farmBurstKeys=").append(s.farmBurstKeys()).append('\n');
            out.append(LookupRunner.status()).append('\n');
            out.append("Rate cells tracked: ").append(s.regions()).append("; cuts evicted from bounded heatmap: ").append(s.evictedHeatCuts()).append('\n');
            out.append("Coordinates are cut-event weighted centers within dimension-specific cubes, not proven machine origins.\n");
            out.append("Counts persist for this process only. This file is an atomic snapshot, not an unbounded event log.\n");
            out.append("The last process report is retained as debug.previous.txt on startup.\n");
            out.append("Internal rollback publications and database retries are exempt. In-flight retry allowance: up to 750 entries in CoreProtect 1.2.\n");
            out.append("No event payloads, chat contents, NBT, or credentials are recorded here.\n");
            out.append("PLAYER_ATTRIBUTED excludes detected farm kills. PLAYER_FACTORY records repeated unnamed farm-mob kills under player names; this is a rate heuristic, not proof of automation.\n");
            out.append("With limit-player-events=false, ordinary player evidence bypasses queue/heap/rate limits. The hard limit is NOT a total queue or memory bound. MAINTENANCE uses the factory backlog limit and is counted separately. Bypassed is the admitted subset while protection was disabled.\n");
            out.append("Use same-process snapshot differences for interval rates; config revision changes mix policies.\n\nIMPACT BY CATEGORY\n");
            s.categories().forEach((key, value) -> out.append(key).append(' ').append(value.summary()).append('\n'));
            out.append("\nIMPACT BY ACTION (first 64 keys plus <other>, process lifetime)\n");
            s.actions().forEach((key, value) -> out.append(key).append(' ').append(value.summary()).append('\n'));
            out.append("\nPLAYER ACTORS (first 128 names plus <other>, process lifetime)\n");
            s.playerActors().forEach((key, value) -> out.append(key).append(' ').append(value.summary()).append('\n'));
            out.append("\nHEATMAP (largest cut counts first)\n");
            for (Limiter.Hotspot h : s.hotspots()) {
                out.append(h.summary()).append('\n');
                out.append("  cell=").append(h.cell()).append(" observedBounds=(").append(h.minX()).append(',').append(h.minY()).append(',').append(h.minZ())
                    .append(")..(").append(h.maxX()).append(',').append(h.maxY()).append(',').append(h.maxZ()).append(")\n");
                out.append("  first=").append(Instant.ofEpochMilli(h.first())).append(" last=").append(Instant.ofEpochMilli(h.last())).append('\n');
                out.append("  reasons=").append(h.reasons()).append(" categories=").append(h.categories()).append(" actors/actions=").append(h.details()).append('\n');
            }
            Files.createDirectories(DIRECTORY);
            Path tmp = DIRECTORY.resolve("debug.txt.tmp");
            Files.writeString(tmp, out);
            try { Files.move(tmp, REPORT, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, REPORT, StandardCopyOption.REPLACE_EXISTING); }
            lastWrite = Instant.now().toString(); lastError = "none";
        } catch (Exception e) { reportFailure(e); }
    }
    private static void reportFailure(Exception e) {
        lastError = EventInfo.safe(e.toString());
        LOG.warning("CoreProtectMeltdown report error: " + lastError);
    }
    public static EventInfo describe(LogEntry e) {
        String farmMob = KillEvidence.farmMob(e.action(), e.data());
        return new EventInfo(e.world(), e.x(), e.y(), e.z(), e.actor(), e.action(), automated(e.actor()), false,
                chars(e.data()) + chars(e.actor()) + chars(e.action()) + chars(e.world()),
                farmMob, "kill".equals(e.action()) && farmMob == null);
    }
    public static EventInfo describe(ContainerLogEntry e) {
        boolean auto = automated(e.actor()) || (e.context() != null && e.context().contains("source=automation"));
        return new EventInfo(e.world(), e.x(), e.y(), e.z(), e.actor(), "container:" + e.action(), auto, false,
            chars(e.itemData()) + chars(e.itemType()) + chars(e.context()) + chars(e.containerType()) + chars(e.actor()) + chars(e.world()));
    }
    public static EventInfo describe(String actor, String action, EntityLocation loc, boolean maintenance, long chars) {
        return new EventInfo(loc == null ? null : loc.worldName(), loc == null ? null : loc.blockX(), loc == null ? null : loc.blockY(),
            loc == null ? null : loc.blockZ(), actor, action, automated(actor), maintenance, chars + chars(actor));
    }
    public static long chars(String s) { return s == null ? 0 : s.length(); }
    public static long payload(EntityContainerTransaction t) {
        long count = 0;
        for (EntityContainerTransaction.ItemSnapshot s : t.getOldContents()) count += chars(s.itemData()) + chars(s.itemType()) + 64;
        for (EntityContainerTransaction.ItemSnapshot s : t.getNewContents()) count += chars(s.itemData()) + chars(s.itemType()) + 64;
        return count;
    }
    private static boolean automated(String actor) { return actor == null || actor.isBlank() || actor.startsWith("#"); }
}

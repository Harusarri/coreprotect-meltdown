package kr.harusarri.coreprotectmeltdown;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Immutable, validated configuration. A failed reload leaves the active settings intact. */
public record GuardConfig(boolean enabled, int cellSize, int regionBurst, int regionPerSecond,
        int playerBurst, int playerPerSecond, int globalBurst, int globalPerSecond,
        int queueSoftLimit, int queueHardLimit, int maxPayloadChars,
        int maxRegions, int maxHeatmapCells, int regionIdleSeconds, int reportSeconds,
        int heapStopPercent, int heapResumePercent, boolean factoryOnly, boolean limitPlayerEvents,
        int factoryQueueLimit, int factoryActivitySeconds, String factoryActors,
        int lookupTimeoutSeconds, int nearDefaultSeconds, int factoryKillBurst, int factoryKillPerSecond) {
    public static GuardConfig defaults() {
        return new GuardConfig(true, 32, 4000, 400, 20000, 2000, 20000, 5000,
                16000, 20000, 32768, 4096, 1024, 600, 30, 88, 80,
                true, false, 1000, 30, "#piston,#hopper,#dispenser,#dropper", 10, 86400, 64, 8);
    }
    public GuardConfig {
        range(cellSize, 8, 128, "cell-size");
        range(regionBurst, 1, 1_000_000, "region-burst");
        range(regionPerSecond, 1, 1_000_000, "region-per-second");
        range(playerBurst, 1, 1_000_000, "player-burst");
        range(playerPerSecond, 1, 1_000_000, "player-per-second");
        range(globalBurst, 1, 1_000_000, "global-burst");
        range(globalPerSecond, 1, 1_000_000, "global-per-second");
        range(queueSoftLimit, 1, 100_000, "queue-soft-limit");
        range(queueHardLimit, queueSoftLimit + 1, 200_000, "queue-hard-limit");
        range(maxPayloadChars, 256, 1_000_000, "max-payload-chars");
        range(maxRegions, 16, 65536, "max-regions");
        range(maxHeatmapCells, 8, 8192, "max-heatmap-cells");
        range(regionIdleSeconds, 10, 86400, "region-idle-seconds");
        range(reportSeconds, 5, 3600, "report-seconds");
        range(heapStopPercent, 50, 98, "heap-stop-percent");
        range(heapResumePercent, 30, heapStopPercent - 1, "heap-resume-percent");
        range(factoryQueueLimit, 1, 100000, "factory-queue-limit");
        range(factoryKillBurst, 1, 100000, "factory-kill-burst");
        range(factoryKillPerSecond, 1, 100000, "factory-kill-per-second");
        range(factoryActivitySeconds, 1, 3600, "factory-activity-seconds");
        range(lookupTimeoutSeconds, 1, 60, "lookup-timeout-seconds");
        range(nearDefaultSeconds, 0, 31536000, "near-default-seconds");
        if (factoryActors == null || factoryActors.length() > 2048 || !factoryActors.matches("#[a-z0-9_-]+(,#[a-z0-9_-]+)*"))
            throw new IllegalArgumentException("factory-actors must be comma-separated #actor names (lowercase, no spaces)");
    }
    private static void range(int value, int min, int max, String key) {
        if (value < min || value > max) throw new IllegalArgumentException(key + " must be " + min + ".." + max);
    }
    public static GuardConfig load(Path path) throws IOException {
        Properties p = new Properties();
        if (!Files.exists(path)) {
            Files.createDirectories(path.getParent());
            try (InputStream in = GuardConfig.class.getResourceAsStream("/coreprotect-meltdown.properties")) {
                if (in == null) throw new IOException("Missing default configuration");
                Files.copy(in, path);
            }
        }
        try (Reader in = Files.newBufferedReader(path)) { p.load(in); }
        GuardConfig d = defaults();
        Set<String> unknown = new TreeSet<>(p.stringPropertyNames());
        unknown.removeAll(d.values().keySet());
        if (!unknown.isEmpty()) throw new IllegalArgumentException("Unknown configuration keys: " + unknown);
        return new GuardConfig(b(p,"enabled",d.enabled), i(p,"cell-size",d.cellSize), i(p,"region-burst",d.regionBurst),
            i(p,"region-per-second",d.regionPerSecond), i(p,"player-burst",d.playerBurst),
            i(p,"player-per-second",d.playerPerSecond), i(p,"global-burst",d.globalBurst),
            i(p,"global-per-second",d.globalPerSecond), i(p,"queue-soft-limit",d.queueSoftLimit),
            i(p,"queue-hard-limit",d.queueHardLimit), i(p,"max-payload-chars",d.maxPayloadChars),
            i(p,"max-regions",d.maxRegions), i(p,"max-heatmap-cells",d.maxHeatmapCells),
            i(p,"region-idle-seconds",d.regionIdleSeconds), i(p,"report-seconds",d.reportSeconds),
            i(p,"heap-stop-percent",d.heapStopPercent), i(p,"heap-resume-percent",d.heapResumePercent),
            b(p,"factory-only",d.factoryOnly), b(p,"limit-player-events",d.limitPlayerEvents),
            i(p,"factory-queue-limit",d.factoryQueueLimit), i(p,"factory-activity-seconds",d.factoryActivitySeconds),
            p.getProperty("factory-actors",d.factoryActors).trim(), i(p,"lookup-timeout-seconds",d.lookupTimeoutSeconds),
            i(p,"near-default-seconds",d.nearDefaultSeconds), i(p,"factory-kill-burst",d.factoryKillBurst),
            i(p,"factory-kill-per-second",d.factoryKillPerSecond));
    }
    private static int i(Properties p, String key, int fallback) {
        try { return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)).trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(key + " must be an integer"); }
    }
    private static boolean b(Properties p, String key, boolean fallback) {
        String value = p.getProperty(key, Boolean.toString(fallback)).trim();
        if (value.equalsIgnoreCase("true")) return true;
        if (value.equalsIgnoreCase("false")) return false;
        throw new IllegalArgumentException(key + " must be true or false");
    }
    /** The on-disk names and current runtime values, in configuration-file order. */
    public Map<String, String> values() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("enabled", Boolean.toString(enabled));
        result.put("cell-size", Integer.toString(cellSize));
        result.put("region-burst", Integer.toString(regionBurst));
        result.put("region-per-second", Integer.toString(regionPerSecond));
        result.put("player-burst", Integer.toString(playerBurst));
        result.put("player-per-second", Integer.toString(playerPerSecond));
        result.put("global-burst", Integer.toString(globalBurst));
        result.put("global-per-second", Integer.toString(globalPerSecond));
        result.put("queue-soft-limit", Integer.toString(queueSoftLimit));
        result.put("queue-hard-limit", Integer.toString(queueHardLimit));
        result.put("max-payload-chars", Integer.toString(maxPayloadChars));
        result.put("max-regions", Integer.toString(maxRegions));
        result.put("max-heatmap-cells", Integer.toString(maxHeatmapCells));
        result.put("region-idle-seconds", Integer.toString(regionIdleSeconds));
        result.put("report-seconds", Integer.toString(reportSeconds));
        result.put("heap-stop-percent", Integer.toString(heapStopPercent));
        result.put("heap-resume-percent", Integer.toString(heapResumePercent));
        result.put("factory-only", Boolean.toString(factoryOnly));
        result.put("limit-player-events", Boolean.toString(limitPlayerEvents));
        result.put("factory-queue-limit", Integer.toString(factoryQueueLimit));
        result.put("factory-activity-seconds", Integer.toString(factoryActivitySeconds));
        result.put("factory-actors", factoryActors);
        result.put("lookup-timeout-seconds", Integer.toString(lookupTimeoutSeconds));
        result.put("near-default-seconds", Integer.toString(nearDefaultSeconds));
        result.put("factory-kill-burst", Integer.toString(factoryKillBurst));
        result.put("factory-kill-per-second", Integer.toString(factoryKillPerSecond));
        return Collections.unmodifiableMap(result);
    }
}

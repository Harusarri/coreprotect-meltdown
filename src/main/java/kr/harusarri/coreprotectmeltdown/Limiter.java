package kr.harusarri.coreprotectmeltdown;

import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;

/** Admission uses queue depth plus reservations for publications currently executing. */
public final class Limiter {
    public enum Reason { REGION_RATE, GLOBAL_RATE, QUEUE_PRESSURE, FACTORY_BACKLOG, HEAP_PRESSURE, PAYLOAD_TOO_LARGE, TRACKING_CAPACITY }
    public enum Category { PLAYER_ATTRIBUTED, AUTOMATION, MAINTENANCE, PLAYER_FACTORY }
    public record Cell(String world, int x, int y, int z, int size) {}
    private final LongSupplier clock;
    private GuardConfig config;
    private Bucket global, players;
    private Set<String> factoryActors;
    private final LinkedHashMap<Cell, Long> factoryCells = new LinkedHashMap<>(128, .75f, true);
    private long factoryAdmitted, factoryCut;
    private record FarmKey(Cell cell, String actor, String type) {}
    private static final class FarmBurst {
        final Bucket bucket;
        long overloadedAt;
        boolean overloaded;
        FarmBurst(int capacity, long now) { bucket = new Bucket(capacity, now); }
    }
    private final LinkedHashMap<FarmKey, FarmBurst> farmBursts = new LinkedHashMap<>(128, .75f, true);
    private final LinkedHashMap<Cell, Region> regions = new LinkedHashMap<>(128, .75f, true);
    private final LinkedHashMap<Cell, Heat> heatmap = new LinkedHashMap<>(128, .75f, true);
    private final EnumMap<Reason, Long> reasons = new EnumMap<>(Reason.class);
    private final EnumMap<Category, Counts> categories = new EnumMap<>(Category.class);
    private final Map<String, Counts> actions = new LinkedHashMap<>(), playerActors = new LinkedHashMap<>();
    private final String processId = UUID.randomUUID().toString();
    private final long startedNanos;
    private long configRevision, configuredAt;
    private long admitted, cut, unlocatedCuts, evictedHeatCuts, started = System.currentTimeMillis(), lastSweep;
    private int peakPending;

    public Limiter(GuardConfig config, LongSupplier clock) {
        this.clock = clock;
        startedNanos = clock.getAsLong();
        for (Category category : Category.values()) categories.put(category, new Counts());
        configure(config);
    }
    public synchronized void configure(GuardConfig value) {
        config = value;
        configRevision++;
        configuredAt = System.currentTimeMillis();
        long now = clock.getAsLong();
        global = new Bucket(value.globalBurst(), now);
        players = new Bucket(value.playerBurst(), now);
        regions.clear(); // No old token budgets survive configuration changes.
        factoryCells.clear();
        farmBursts.clear();
        factoryActors = Set.copyOf(Arrays.asList(value.factoryActors().split(",")));
        while (heatmap.size() > value.maxHeatmapCells()) evictHeat();
    }
    public synchronized GuardConfig config() { return config; }

    /** Caller must reserve capacity here until publication completes, or hold the monitor through enqueue. */
    public synchronized boolean allow(EventInfo e, int pending, boolean heapPressure) {
        peakPending = Math.max(peakPending, pending);
        if (!config.enabled()) { admitted++; recordImpact(e, false, null, true); return true; }
        long now = clock.getAsLong();
        boolean factory = isFactory(e, now);
        boolean player = !e.automated() && !e.maintenance();
        boolean protectedAudit = !config.limitPlayerEvents() && ((player && !factory) || e.auditCritical());
        // Factory records attributed to a player consume automation budgets, never player budgets.
        boolean automationBudget = e.automated() || factory;
        boolean restricted = !protectedAudit && !e.maintenance()
            && (automationBudget ? (!config.factoryOnly() || factory) : config.limitPlayerEvents());
        Reason reason = null;
        if (!protectedAudit) {
            if (pending >= config.queueHardLimit()) reason = Reason.QUEUE_PRESSURE;
            else if (heapPressure) reason = Reason.HEAP_PRESSURE;
            else if ((factory || e.maintenance()) && pending >= Math.min(config.factoryQueueLimit(), config.queueSoftLimit()))
                reason = Reason.FACTORY_BACKLOG;
            // Unrecognized automation must not consume the reserve for player evidence.
            else if ((e.automated() || e.maintenance()) && pending >= config.queueSoftLimit()) reason = Reason.QUEUE_PRESSURE;
            else if (restricted && e.payloadChars() > config.maxPayloadChars()) reason = Reason.PAYLOAD_TOO_LARGE;
        }

        Region region = null;
        if (reason == null && restricted) {
            if (now - lastSweep >= 1_000_000_000L) {
                lastSweep = now;
                Iterator<Region> it = regions.values().iterator();
                for (int n = 0; n < 64 && it.hasNext(); n++) {
                    Region r = it.next();
                    if (now - r.touched <= config.regionIdleSeconds() * 1_000_000_000L) break;
                    it.remove();
                }
            }
            if (e.located()) {
                Cell key = cell(e);
                region = regions.get(key);
                if (region == null && regions.size() >= config.maxRegions()) reason = Reason.TRACKING_CAPACITY;
                else if (region == null) {
                    region = new Region(config, now);
                    regions.put(key, region);
                }
                if (region != null) {
                    region.touched = now;
                    Bucket b = automationBudget ? region.automation : region.player;
                    b.refill(now, automationBudget ? config.regionBurst() : config.playerBurst(),
                                  automationBudget ? config.regionPerSecond() : config.playerPerSecond());
                    if (b.tokens < 1) reason = Reason.REGION_RATE;
                }
            }
            Bucket g = automationBudget ? global : players;
            g.refill(now, automationBudget ? config.globalBurst() : config.playerBurst(),
                          automationBudget ? config.globalPerSecond() : config.playerPerSecond());
            if (reason == null && g.tokens < 1) reason = Reason.GLOBAL_RATE;
            if (reason == null) {
                g.tokens--;
                if (region != null) (automationBudget ? region.automation : region.player).tokens--;
            }
        }
        if (reason != null) {
            if (factory) factoryCut++;
            recordImpact(e, factory, reason, false);
            recordCut(e, factory, reason);
            return false;
        }
        admitted++;
        if (factory) factoryAdmitted++;
        recordImpact(e, factory, null, false);
        return true;
    }
    private boolean isFactory(EventInfo e, long now) {
        if (e.auditCritical()) return false;
        if (!e.automated() && !e.maintenance()) return repeatedFarmKill(e, now);
        if (e.maintenance()) return false;
        boolean machine = (e.actor() != null && factoryActors.contains(e.actor())) || (e.action() != null
            && (e.action().startsWith("container:") || e.action().equals("entity_container")));
        if (!e.located()) return machine;
        Cell cell = cell(e);
        if (machine) {
            if (!factoryCells.containsKey(cell) && factoryCells.size() >= config.maxRegions()) factoryCells.remove(factoryCells.keySet().iterator().next());
            factoryCells.put(cell, now);
            return true;
        }
        Long last = factoryCells.get(cell);
        if (last == null) return false;
        if (now - last > config.factoryActivitySeconds() * 1_000_000_000L) { factoryCells.remove(cell); return false; }
        return true; // Only automatic events share a machine's cell; named player events never do.
    }
    private boolean repeatedFarmKill(EventInfo e, long now) {
        if (e.farmMobType() == null || !e.located()) return false;
        FarmKey key = new FarmKey(cell(e), EventInfo.safe(e.actor()), e.farmMobType());
        FarmBurst burst = farmBursts.get(key);
        if (burst == null) {
            if (farmBursts.size() >= config.maxRegions()) farmBursts.remove(farmBursts.keySet().iterator().next());
            burst = new FarmBurst(config.factoryKillBurst(), now);
            farmBursts.put(key, burst);
        }
        burst.bucket.refill(now, config.factoryKillBurst(), config.factoryKillPerSecond());
        if (burst.bucket.tokens < 1) { burst.overloadedAt = now; burst.overloaded = true; }
        else burst.bucket.tokens--;
        return burst.overloaded && now - burst.overloadedAt < config.factoryActivitySeconds() * 1_000_000_000L;
    }
    private static Category category(EventInfo e, boolean factory) {
        return e.maintenance() ? Category.MAINTENANCE : e.automated() ? Category.AUTOMATION
            : factory ? Category.PLAYER_FACTORY : Category.PLAYER_ATTRIBUTED;
    }
    private void recordImpact(EventInfo e, boolean factory, Reason reason, boolean bypassed) {
        Category category = category(e, factory);
        categories.get(category).add(reason, bypassed);
        bounded(actions, category + "/" + EventInfo.safe(e.action()), 64).add(reason, bypassed);
        if (!e.automated() && !e.maintenance()) bounded(playerActors, EventInfo.safe(e.actor()), 128).add(reason, bypassed);
    }
    private static Counts bounded(Map<String, Counts> map, String key, int limit) {
        if (!map.containsKey(key) && map.size() >= limit) key = "<other>";
        return map.computeIfAbsent(key, unused -> new Counts());
    }
    private static <K> Map<K, Impact> copyCounts(Map<K, Counts> source) {
        Map<K, Impact> copy = new LinkedHashMap<>();
        source.forEach((key, counts) -> copy.put(key, counts.copy()));
        return Collections.unmodifiableMap(copy);
    }
    private Cell cell(EventInfo e) {
        int s = config.cellSize();
        return new Cell(EventInfo.safe(e.world()), Math.floorDiv(e.x(), s), Math.floorDiv(e.y(), s), Math.floorDiv(e.z(), s), s);
    }
    private void recordCut(EventInfo e, boolean factory, Reason reason) {
        cut++;
        reasons.merge(reason, 1L, Long::sum);
        if (!e.located()) { unlocatedCuts++; return; }
        Cell key = cell(e);
        Heat h = heatmap.get(key);
        if (h == null) {
            if (heatmap.size() >= config.maxHeatmapCells()) evictHeat();
            h = new Heat(key);
            heatmap.put(key, h);
        }
        h.add(e, category(e, factory), reason);
    }
    public synchronized void recordTestHotspot(String world, int x, int y, int z) {
        EventInfo e = new EventInfo(world, x, y, z, "#piston", "block_place", true, false, 32);
        factoryCut++;
        recordImpact(e, true, Reason.REGION_RATE, false);
        recordCut(e, true, Reason.REGION_RATE);
    }
    private void evictHeat() {
        Iterator<Heat> it = heatmap.values().iterator();
        if (it.hasNext()) { evictedHeatCuts += it.next().cuts; it.remove(); }
    }
    public synchronized Snapshot snapshot() {
        List<Hotspot> cells = heatmap.values().stream().map(Heat::copy)
            .sorted(Comparator.comparingLong(Hotspot::cuts).reversed()).toList();
        return new Snapshot(Instant.ofEpochMilli(started).toString(), admitted, cut, unlocatedCuts,
            evictedHeatCuts, peakPending, regions.size(), Map.copyOf(reasons), cells, config,
            processId, Math.max(0L, clock.getAsLong() - startedNanos) / 1_000_000_000.0,
            configRevision, Instant.ofEpochMilli(configuredAt).toString(), copyCounts(categories), copyCounts(actions), copyCounts(playerActors), factoryAdmitted, factoryCut, factoryCells.size(), farmBursts.size());
    }
    public record Snapshot(String since, long admitted, long cut, long unlocatedCuts, long evictedHeatCuts,
            int peakPending, int regions, Map<Reason, Long> reasons, List<Hotspot> hotspots, GuardConfig config,
            String processId, double uptimeSeconds, long configRevision, String configuredAt,
            Map<Category, Impact> categories, Map<String, Impact> actions, Map<String, Impact> playerActors,
            long factoryAdmitted, long factoryCut, int factoryCells, int farmBurstKeys) {}
    public record Impact(long admitted, long cut, long bypassed, Map<Reason, Long> reasons) {
        public long attempted() { return admitted + cut; }
        public double cutPercent() { return attempted() == 0 ? 0 : 100.0 * cut / attempted(); }
        public String summary() {
            return String.format(Locale.ROOT, "attempted=%d admitted=%d cut=%d bypassed=%d cutPercent=%.6f reasons=%s",
                attempted(), admitted, cut, bypassed, cutPercent(), reasons);
        }
    }
    private static final class Counts {
        long admitted, cut, bypassed;
        final EnumMap<Reason, Long> reasons = new EnumMap<>(Reason.class);
        void add(Reason reason, boolean bypass) {
            if (reason == null) { admitted++; if (bypass) bypassed++; }
            else { cut++; reasons.merge(reason, 1L, Long::sum); }
        }
        Impact copy() { return new Impact(admitted, cut, bypassed, Map.copyOf(reasons)); }
    }
    public record Hotspot(Cell cell, long cuts, double x, double y, double z, int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ, long first, long last, Map<String, Long> details, Map<Reason, Long> reasons, Map<Category, Long> categories) {
        public String summary() {
            return String.format(Locale.ROOT, "%s center=(%.1f, %.1f, %.1f) cut=%,d", cell.world(), x, y, z, cuts);
        }
    }
    private static final class Bucket {
        double tokens; long last;
        Bucket(int capacity, long now) { tokens = capacity; last = now; }
        void refill(long now, int capacity, int rate) {
            tokens = Math.min(capacity, tokens + Math.max(0L, now - last) / 1_000_000_000.0 * rate);
            last = now;
        }
    }
    private static final class Region {
        final Bucket automation, player; long touched;
        Region(GuardConfig c, long now) {
            automation = new Bucket(c.regionBurst(), now); player = new Bucket(c.playerBurst(), now); touched = now;
        }
    }
    private static final class Heat {
        final Cell cell;
        long cuts, first, last;
        double x, y, z;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        final Map<String, Long> details = new LinkedHashMap<>();
        final EnumMap<Reason, Long> reasons = new EnumMap<>(Reason.class);
        final EnumMap<Category, Long> categories = new EnumMap<>(Category.class);
        Heat(Cell cell) { this.cell = cell; }
        void add(EventInfo e, Category category, Reason reason) {
            long now = System.currentTimeMillis();
            if (cuts == 0) first = now;
            last = now; cuts++;
            x += (e.x() - x) / cuts; y += (e.y() - y) / cuts; z += (e.z() - z) / cuts;
            minX = Math.min(minX, e.x()); minY = Math.min(minY, e.y()); minZ = Math.min(minZ, e.z());
            maxX = Math.max(maxX, e.x()); maxY = Math.max(maxY, e.y()); maxZ = Math.max(maxZ, e.z());
            String detail = EventInfo.safe(e.actor()) + "/" + EventInfo.safe(e.action());
            if (!details.containsKey(detail) && details.size() >= 8) detail = "<other>";
            details.merge(detail, 1L, Long::sum); reasons.merge(reason, 1L, Long::sum);
            categories.merge(category, 1L, Long::sum);
        }
        Hotspot copy() { return new Hotspot(cell, cuts, x,y,z,minX,minY,minZ,maxX,maxY,maxZ,first,last,Map.copyOf(details),Map.copyOf(reasons),Map.copyOf(categories)); }
    }
}

package kr.harusarri.coreprotectmeltdown;

import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;

import static kr.harusarri.coreprotectmeltdown.LimiterTest.check;

final class ImpactTest {
    static void run() throws Exception {
        AtomicLong clock = new AtomicLong();
        Path policy = Files.createTempFile("legacy-limits", ".properties");
        Files.writeString(policy, "limit-player-events=true\n");
        Limiter l = new Limiter(GuardConfig.load(policy), clock::get);
        Files.delete(policy);
        l.allow(LimiterTest.player(1), 0, false);
        l.allow(LimiterTest.player(1), 20000, false);
        l.allow(LimiterTest.auto(1,2,3), 16000, false);
        l.allow(new EventInfo(null,null,null,null,"#maintenance","update",true,true,0), 0, false);
        clock.set(5_000_000_000L);
        var before = l.snapshot();
        var p = before.categories().get(Limiter.Category.PLAYER_ATTRIBUTED);
        check(p.attempted()==2 && p.admitted()==1 && p.cut()==1 && p.cutPercent()==50, "player denominator excludes automation and maintenance");
        check(p.reasons().get(Limiter.Reason.QUEUE_PRESSURE)==1, "player cut reason preserved");
        check(before.playerActors().get("Alice").equals(p), "actor uses the same player denominator");
        check(before.actions().get("PLAYER_ATTRIBUTED/block_place").equals(p), "action uses matching denominator");
        check(before.uptimeSeconds()==5 && before.configRevision()==1, "snapshot supplies interval metadata");
        check(before.hotspots().stream().mapToLong(h -> h.categories().getOrDefault(Limiter.Category.PLAYER_ATTRIBUTED,0L)).sum()==1, "player cuts distinguishable in heatmap");
        for (int i=0;i<10000;i++) l.allow(new EventInfo(null,null,null,null,"Player"+i,"action"+i,false,false,1),20000,false);
        var after = l.snapshot();
        check(after.playerActors().size()==129 && after.actions().size()==65, "actor/action cardinality remains bounded");
        check(after.playerActors().get("<other>").cut()>9000, "overflow is counted rather than silently lost");
        check(after.playerActors().values().stream().mapToLong(Limiter.Impact::attempted).sum()==10002, "actor overflow conserves totals");
        check(after.actions().values().stream().mapToLong(Limiter.Impact::attempted).sum()==10004, "action overflow conserves totals");
        check(after.categories().values().stream().mapToLong(Limiter.Impact::admitted).sum()==after.admitted(), "category admitted total conserved");
        check(after.categories().values().stream().mapToLong(Limiter.Impact::cut).sum()==after.cut(), "category cut total conserved");
        check(before.playerActors().get("Alice").attempted()==2, "published snapshot stays immutable after new events");
        l.configure(GuardConfig.defaults());
        check(l.snapshot().configRevision()==2 && l.snapshot().cut()==after.cut() && l.snapshot().processId().equals(before.processId()), "reload preserves counters and identifies policy changes");

        Path root = Files.createTempDirectory("meltdown-migration");
        try {
            Path old = Files.createDirectory(root.resolve("coreprotect-guard"));
            Files.writeString(old.resolve("config.properties"), "enabled=false\nplayer-per-second=42\n");
            Files.writeString(old.resolve("debug.txt"), "previous report");
            ConfigMigration.migrate(root);
            Path current = root.resolve("coreprotect-meltdown");
            check(!Files.exists(old) && Files.readString(current.resolve("debug.txt")).equals("previous report"), "rename preserves previous report");
            GuardConfig disabled = GuardConfig.load(current.resolve("config.properties"));
            check(!disabled.enabled() && disabled.playerPerSecond()==42, "rename preserves user settings");
            l.configure(disabled);
            check(l.allow(LimiterTest.player(1),999999,true), "disabled bypass accepted");
            check(l.snapshot().categories().get(Limiter.Category.PLAYER_ATTRIBUTED).bypassed()==1, "disabled subset counted separately");
            Files.createDirectory(old);
            Files.writeString(old.resolve("config.properties"), "enabled=true\n");
            ConfigMigration.migrate(root);
            check(!GuardConfig.load(current.resolve("config.properties")).enabled(), "existing new settings never overwritten by legacy");
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
        }
    }
}

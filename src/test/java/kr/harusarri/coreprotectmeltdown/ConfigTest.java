package kr.harusarri.coreprotectmeltdown;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;

public final class ConfigTest {
    public static void run() throws Exception {
        Path directory = Files.createTempDirectory("cpguard-config-test");
        Path file = directory.resolve("config.properties");
        try {
            GuardConfig generated = GuardConfig.load(file);
            LimiterTest.check(generated.equals(GuardConfig.defaults()), "documented defaults match generated configuration");
            LimiterTest.check(Files.readString(file).contains("설정"), "UTF-8 configuration comments preserved");
            Files.writeString(file, "region-per-second=123\nplayer-per-second=456\n");
            GuardConfig legacy = GuardConfig.load(file);
            LimiterTest.check(legacy.enabled() && legacy.regionPerSecond()==123 && legacy.playerPerSecond()==456, "legacy configuration retained");
            Limiter active = new Limiter(legacy, new AtomicLong()::get);
            EventInfo event = new EventInfo("world", 1, 2, 3, "#piston", "place", true, false, 1000000);
            LimiterTest.check(!active.allow(event, 20000, true), "enabled guard cuts overload");
            Files.writeString(file, "enabled=false\nregion-per-second=42\n");
            active.configure(GuardConfig.load(file));
            LimiterTest.check(active.allow(event, 999999, true), "explicitly disabled guard bypasses every cutoff");
            LimiterTest.check(active.snapshot().cut()==1, "reload retains earlier cut statistics");
            LimiterTest.check(active.config().values().get("enabled").equals("false"), "active display reflects disabled state");
            for (String invalid : new String[]{"enabled=maybe", "region-per-seconds=10", "queue-soft-limit=100\nqueue-hard-limit=50", "report-seconds=banana"}) {
                Files.writeString(file, invalid);
                GuardConfig before = active.config();
                try { active.configure(GuardConfig.load(file)); throw new AssertionError("Invalid config accepted: " + invalid); }
                catch (IllegalArgumentException expected) { LimiterTest.check(active.config().equals(before), "invalid reload is atomic"); }
            }
            Files.writeString(file, "enabled=true\nregion-per-second=42\n");
            active.configure(GuardConfig.load(file));
            LimiterTest.check(!active.allow(event, 999999, true), "reenabling restores protection immediately");
            LimiterTest.check(active.config().values().get("region-per-second").equals("42"), "display uses exact file keys");
            LimiterTest.check(!MeltdownI18n.englishMap().isEmpty(), "en_us language file loaded");
            LimiterTest.check(MeltdownI18n.englishMap().keySet().equals(MeltdownI18n.koreanMap().keySet()), "en_us and ko_kr have identical key sets");
            LimiterTest.check(MeltdownI18n.getByLang(MeltdownI18n.EN_US, "cmd.btn.refresh").contains("Refresh"), "en_us translation works");
            LimiterTest.check(MeltdownI18n.getByLang(MeltdownI18n.KO_KR, "cmd.btn.refresh").contains("새로고침"), "ko_kr translation works");
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
    }
}

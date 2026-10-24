package kr.harusarri.coreprotectmeltdown;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicLong;
import static kr.harusarri.coreprotectmeltdown.LimiterTest.check;
final class FactoryTest {
    static void run() throws Exception {
        AtomicLong clock = new AtomicLong();
        Limiter limiter = new Limiter(GuardConfig.defaults(), clock::get);
        check(limiter.allow(new EventInfo(null,null,null,null,null,null,true,false,0),0,false), "unattributed event is null-safe");
        EventInfo water = new EventInfo("overworld", 1,64,1,"#water","block_place",true,false,20);
        check(!limiter.allow(water,16000,false), "unclassified automation preserves player reserve");
        check(!limiter.allow(LimiterTest.auto(1,64,1),1000,false), "machine backlog is cut early");
        check(!limiter.allow(water,1000,false), "machine-cell water cascade is treated as factory");
        check(limiter.allow(new EventInfo("overworld",65,64,1,"#water","block_place",true,false,20),999,false), "other cells are independent");
        for (int i=0;i<30000;i++) check(limiter.allow(LimiterTest.player(i*32),16000,false), "players bypass rate, factory and tracking limits");
        check(limiter.allow(LimiterTest.player(1),20000,false), "full queue preserves player evidence");
        check(limiter.allow(LimiterTest.player(1),0,true), "heap pressure preserves player evidence");
        clock.set(31_000_000_000L);
        check(limiter.allow(water,1000,false), "factory association expires after activity stops");
        check(limiter.snapshot().factoryCut()==2, "factory subset counts exclude emergency player cuts");
        Path file = Files.createTempFile("meltdown-factory-test", ".properties");
        try {
            Files.writeString(file,"factory-only=false\nlimit-player-events=true\nplayer-burst=1\nplayer-per-second=1\n");
            limiter.configure(GuardConfig.load(file));
            check(!limiter.allow(water,16000,false), "legacy automation scope can be restored");
            check(limiter.allow(LimiterTest.player(1),0,false) && !limiter.allow(LimiterTest.player(1),0,false), "player rate limits can be explicitly restored");
            Files.writeString(file,"factory-actors=#piston,#piston\n");
            limiter.configure(GuardConfig.load(file));
            check(!limiter.allow(LimiterTest.auto(1,64,1),1000,false), "duplicate actor names do not break atomic reload");
            for (String invalid : new String[]{"factory-actors=#piston, bad", "lookup-timeout-seconds=0", "near-default-seconds=-1"}) {
                Files.writeString(file,invalid);
                try { GuardConfig.load(file); throw new AssertionError("invalid configuration accepted"); }
                catch (IllegalArgumentException expected) { check(true,"new settings validated"); }
            }
        } finally { Files.delete(file); }
    }
}

package kr.harusarri.coreprotectmeltdown;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class LimiterTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
    static EventInfo auto(int x, int y, int z) { return new EventInfo("overworld",x,y,z,"#piston","block_place",true,false,20); }
    static EventInfo player(int x) { return new EventInfo("overworld",x,64,0,"Alice","block_place",false,false,20); }
    static Limiter create(AtomicLong clock) { return new Limiter(GuardConfig.defaults(),clock::get); }
    public static void main(String[] args) throws Exception {
        AtomicLong clock = new AtomicLong();
        Limiter l = create(clock);
        for (int i=0;i<4000;i++) check(l.allow(auto(1,64,1),0,false),"burst must fit");
        check(!l.allow(auto(1,64,1),0,false),"4001st event must be cut");
        check(l.allow(player(1),0,false),"player bucket independent from factory");
        clock.addAndGet(1_000_000_000L);
        for (int i=0;i<400;i++) check(l.allow(auto(1,64,1),0,false),"400 tokens per second");
        check(!l.allow(auto(1,64,1),0,false),"continuous rate bounded");
        check(l.allow(auto(33,64,1),0,false),"other region unaffected");
        check(l.allow(new EventInfo("nether",1,64,1,"#piston","place",true,false,1),0,false),"other dimension independent");
        check(!l.allow(auto(1,64,1),16000,false),"soft queue cap");
        check(l.allow(player(1),16000,false),"player queue reserve");
        check(l.allow(player(1),20000,false),"player evidence bypasses saturated queue");
        check(l.allow(player(1),0,true),"player evidence bypasses heap circuit");
        check(l.allow(new EventInfo("overworld",0,0,0,"Alice","sign",false,false,999999),0,false),"player payload exempt from normal factory limits");
        EventInfo internal = new EventInfo("overworld",1,64,1,"#maintenance","entity_update",true,true,1);
        check(!l.allow(internal,16000,false),"maintenance cannot occupy player reserve");
        check(!l.allow(internal,20000,false),"maintenance cannot grow without bound");

        l = create(new AtomicLong());
        for (int cell=0;cell<5;cell++) for (int i=0;i<4000;i++)
            check(l.allow(auto(cell*32,64,0),0,false),"distributed factory budget");
        check(!l.allow(auto(5*32,64,0),0,false),"global cap catches many factories");
        check(l.allow(player(1000),0,false),"global automation cap leaves player budget");
        check(l.snapshot().reasons().get(Limiter.Reason.GLOBAL_RATE)==1,"global reason recorded");

        l = create(clock);
        check(!l.allow(auto(-1,-1,-1),20000,false),"negative coordinates cut");
        check(!l.allow(auto(-3,-5,-7),20000,false),"negative coordinates cut 2");
        var h = l.snapshot().hotspots().getFirst();
        check(h.cell().x()==-1 && h.cell().y()==-1 && h.cell().z()==-1,"floor division for negative cell");
        check(h.x()==-2 && h.y()==-3 && h.z()==-4,"weighted center exact");
        check(h.minX()==-3 && h.maxX()==-1 && h.cuts()==2,"bounds and counts");
        check(!l.allow(new EventInfo(null,null,null,null,"#unknown","log",true,false,1),20000,false),"unlocated is still capped");
        check(l.snapshot().unlocatedCuts()==1,"unknown location tracked");

        l = create(clock);
        for(int i=0;i<100_000;i++) l.allow(auto(i*32,64,0),20000,false);
        check(l.snapshot().hotspots().size()==1024,"heatmap bounded during coordinate flood");
        check(l.snapshot().evictedHeatCuts()==98_976,"evictions accounted, total not lost");
        l = create(clock);
        for(int i=0;i<4096;i++) check(l.allow(auto(i*32,64,0),0,false),"tracked region capacity");
        check(!l.allow(auto(4096*32,64,0),0,false),"no unbounded region allocation");
        clock.addAndGet(601_000_000_000L);
        check(l.allow(auto(4096*32,64,0),0,false),"expired region slots recovered");

        Limiter concurrent = create(new AtomicLong());
        AtomicInteger pending = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        List<Future<?>> tasks = new ArrayList<>();
        long start = System.nanoTime();
        for(int t=0;t<8;t++) tasks.add(workers.submit(() -> {
            for(int i=0;i<125_000;i++) synchronized(concurrent) {
                if(concurrent.allow(internal,pending.get(),false)) pending.incrementAndGet();
            }
        }));
        for(Future<?> task:tasks) task.get(); workers.shutdown();
        check(pending.get()==1000,"atomic enqueue budget under 8 producers / one million inputs");
        check(concurrent.snapshot().cut()==999000,"all rejected inputs counted once");
        pending.addAndGet(-1000);
        synchronized(concurrent) { check(concurrent.allow(internal,pending.get(),false),"resumes after drain"); }
        long elapsed=System.nanoTime()-start;
        try { new GuardConfig(true,0,1,1,1,1,1,1,1,2,256,16,8,10,5,88,80,true,false,1000,30,"#piston",10,86400,64,8); throw new AssertionError("invalid config accepted"); }
        catch(IllegalArgumentException expected) { checks++; }
        check(EventInfo.safe("abc\n\u001b").equals("abc ?"),"diagnostic text sanitization");
        ConfigTest.run();
        ImpactTest.run();
        FactoryTest.run();
        PriorityTest.run();
        System.out.println("PASS "+checks+" checks; 1,000,000 concurrent inputs; background bounded at 1,000; "+elapsed/1_000_000+" ms; -Xmx128m");
    }
}

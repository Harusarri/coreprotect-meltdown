package kr.harusarri.coreprotectmeltdown;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static kr.harusarri.coreprotectmeltdown.LimiterTest.check;

final class PriorityTest {
    static EventInfo kill(String actor, int x, String data) {
        return new EventInfo("overworld", x, -10, -18940, actor, "kill", actor.startsWith("#"), false,
            data.length(), KillEvidence.farmMob("kill", data), KillEvidence.critical("kill", data));
    }
    static void run() throws Exception {
        AtomicLong negative = new AtomicLong(Long.MIN_VALUE + 1000);
        Limiter signedClock = new Limiter(GuardConfig.defaults(), negative::get);
        EventInfo negativeFarm = kill("Farmer",73488,"type=minecraft:zombie");
        for(int i=0;i<64;i++) check(signedClock.allow(negativeFarm,20000,true), "nanoTime may be negative; initial kills stay protected");
        check(!signedClock.allow(negativeFarm,1000,false), "negative-clock repetition still detected");
        negative.addAndGet(31_000_000_000L);
        check(signedClock.allow(negativeFarm,20000,true), "negative-clock detection expires correctly");
        AtomicLong time = new AtomicLong();
        Limiter l = new Limiter(GuardConfig.defaults(), time::get);
        EventInfo farm = kill("Farmer",73488,"type=minecraft:zombie custom_name_visible=false");
        for (int i=0;i<64;i++) check(l.allow(farm,20000,false), "ordinary kills are retained before repetition is established");
        for (int i=0;i<250000;i++) check(!l.allow(farm,1000,false), "detected named farm cannot bypass factory backlog");
        check(l.snapshot().categories().get(Limiter.Category.PLAYER_FACTORY).cut()==250000,"farm cuts separated from ordinary player impact");
        check(l.allow(LimiterTest.player(73488),20000,true),"same factory location never reclassifies direct player edits");
        check(l.allow(kill("Farmer",74000,"type=minecraft:zombie"),20000,true),"new location has an independent kill budget");
        check(l.allow(kill("OtherPlayer",73488,"type=minecraft:zombie"),20000,true),"different player remains independent");
        for (String data : new String[]{"type=minecraft:player player=VmljdGlt", "type=minecraft:zombie custom_name=UGV0",
                "type=minecraft:cow owner=owner-id", "type=minecraft:pig owner_uuid=owner-id", "type=minecraft:wolf",
                "type=minecraft:cat", "type=minecraft:villager", "type=mod:unknown_mob", "malformed"}) {
            for (String actor : new String[]{"Farmer", "#lava"}) {
                EventInfo e=kill(actor,73488,data);
                for(int i=0;i<100;i++) check(l.allow(e,30000,true),"critical death evidence always retained: "+data);
            }
        }
        check(KillEvidence.farmMob("kill","type=minecraft:zombie owner_uuid=x") == null,"owner_uuid is not confused with owner");
        check(KillEvidence.farmMob("kill","type=minecraft:zombie custom_name_visible=false") != null,"visibility flag alone does not mark a named pet");
        check(KillEvidence.farmMob("block_place","type=minecraft:zombie") == null,"only actual kills can be inferred as farms");
        time.set(31_000_000_000L);
        check(l.allow(farm,20000,true),"quiet factory detection expires");
        for(int i=0;i<5000;i++) l.allow(kill("Farmer"+i,i*32,"type=minecraft:zombie"),0,false);
        check(l.snapshot().farmBurstKeys()==4096,"factory detection memory stays bounded");

        Limiter concurrent = new Limiter(GuardConfig.defaults(), () -> 0L);
        AtomicInteger pending = new AtomicInteger(20000), playerCount = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        var tasks = new java.util.ArrayList<Future<?>>();
        for(int t=0;t<8;t++) {
            final int producer=t;
            tasks.add(pool.submit(() -> {
                for(int i=0;i<10000;i++) synchronized(concurrent) {
                    EventInfo e=producer%2==0 ? LimiterTest.player(i) : LimiterTest.auto(i,64,0);
                    if(concurrent.allow(e,pending.get(),true)) {
                        pending.incrementAndGet();
                        if(producer%2==0) playerCount.incrementAndGet();
                    }
                }
            }));
        }
        for(var task:tasks) task.get();
        pool.shutdown();
        check(playerCount.get()==40000 && pending.get()==60000,"all concurrent player evidence survives full queue and heap pressure");
        check(concurrent.snapshot().categories().get(Limiter.Category.PLAYER_ATTRIBUTED).cut()==0,"zero normal player cuts under concurrent overload");
        check(concurrent.snapshot().categories().get(Limiter.Category.AUTOMATION).cut()==40000,"factory input remains blocked");
    }
}

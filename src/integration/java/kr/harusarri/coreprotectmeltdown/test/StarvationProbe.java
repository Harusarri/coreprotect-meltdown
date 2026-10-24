package kr.harusarri.coreprotectmeltdown.test;

import java.util.*;
import net.coreprotect.CoreProtect;
import net.coreprotect.command.Subcommand;
import net.coreprotect.consumer.*;
import net.coreprotect.model.entity.*;
import net.coreprotect.platform.PlatformSender;
import kr.harusarri.coreprotectmeltdown.*;

/** Regression against the real runtime/Consumer, with a separate SQLite database. */
public final class StarvationProbe implements Subcommand {
    public String name() { return "starvation-test"; }
    public boolean canUse(PlatformSender s, List<String> args) { return !s.isPlayer() && !s.isCommandBlock(); }
    public void execute(PlatformSender sender, String label, List<String> args) {
        boolean baseline = args.contains("baseline");
        var runtime = CoreProtect.getInstance().runtime();
        var consumer = runtime.consumer();
        long started = System.nanoTime(), stamp = System.currentTimeMillis();
        String world = "minecraft:overworld";
        var loc = new EntityLocation(world, 73488, -10, -18940, 0, 0);
        Consumer.setPaused(true);
        try (var lease = consumer.acquireMaintenance()) {
            Probe.check(consumer.pending() == 0, "requires an empty test queue");
            GuardService.LIMITER.configure(GuardConfig.defaults());
            // Machine activity, named mob-farm deaths, and entity bookkeeping were distinct bypasses.
            for (int i=0; i<100000; i++) runtime.log(new LogEntry(stamp,"#piston","block_place",world,73488,-10,-18940,"type=minecraft:stone"));
            for (int i=0; i<250000; i++) runtime.log(new LogEntry(stamp,"AuditFarmer","kill",world,73488,-10,-18940,"type=minecraft:zombie"));
            for (int i=0; i<100000; i++) runtime.updateEntityLocation(EntitySpawnData.location(new UUID(444, 1),loc,0));
            int farmQueue = consumer.pending();
            // Unclassified automatic events outside the factory cannot use the player reserve.
            for (int i=0; i<30000; i++) runtime.log(new LogEntry(stamp,"#water","block_place",world,-100000+i*32,64,100000,"type=minecraft:water"));
            int backgroundQueue = consumer.pending();
            long cutBefore = GuardService.LIMITER.snapshot().cut();
            // Interleaving direct audit evidence after saturation, both beside and far from the farm.
            for (int i=0; i<1000; i++) {
                String actor = i%2==0 ? "AuditAlice" : "AuditFarmer";
                int x = i%2==0 ? 200+i : 73488;
                int y = i%2==0 ? 64 : -10, z = i%2==0 ? 200 : -18940;
                runtime.log(new LogEntry(stamp,actor,"block_place",world,x,y,z,"type=minecraft:stone"));
                runtime.log(new LogEntry(stamp,actor,"block_break",world,x,y,z,"type=minecraft:dirt"));
                runtime.logContainer(new ContainerLogEntry(stamp,actor,i%2==0?"add":"remove",world,x+i,y,z,"minecraft:chest","minecraft:diamond","",1));
                runtime.log(new LogEntry(stamp,actor,"kill",world,x,y,z,"type=minecraft:player player=QXVkaXRWaWN0aW0"));
                runtime.logTrackedEntityKill(new LogEntry(stamp,actor,"kill",world,x,y,z,"type=minecraft:cow custom_name=QXVkaXRQZXQ"),new UUID(555,i+1),new EntityLocation(world,x,y,z,0,0));
                runtime.log(new LogEntry(stamp,actor,"item",world,x,y,z,"type=minecraft:diamond amount=1 item_action=pickup"));
                runtime.log(new LogEntry(stamp,actor,"sign",world,x,y,z,"type=minecraft:oak_sign line_1=QXVkaXQ"));
            }
            // Exercise entity ingress with actors and player-death evidence with a non-player cause.
            int interactions = 0;
            for (int i=0; i<25; i++) {
                UUID id = new UUID(666, i+1);
                runtime.logEntitySpawn("AuditAlice",EntitySpawnData.log(id,"minecraft:pig",loc));
                if (runtime.logEntityInteraction("AuditAlice",new EntityInteraction(id,"minecraft:pig",loc,loc,EntityInteractionAction.GENERIC,null))) interactions++;
                runtime.logEntityContainer("AuditAlice",new EntityContainerTransaction(id,loc,List.of(),List.of()));
                runtime.log(new LogEntry(stamp,"#lava","kill",world,73488,-10,-18940,"type=minecraft:player player=QXVkaXRWaWN0aW0"));
            }
            long auditCuts = GuardService.LIMITER.snapshot().cut()-cutBefore;
            int finalQueue = consumer.pending();
            if (baseline) {
                Probe.check(farmQueue==20000 && auditCuts==7100 && interactions==0, "old defect reproduced exactly");
            } else {
                Probe.check(farmQueue<=1100, "factory cannot occupy 20000 slots: "+farmQueue);
                Probe.check(backgroundQueue==16000, "background stops before player reserve: "+backgroundQueue);
                Probe.check(auditCuts==0 && interactions==25, "all player evidence survives saturation");
                Probe.check(finalQueue==23100, "player evidence survives even above old hard cap: "+finalQueue);
            }
            GuardService.requestReport();
            sender.sendMessage("STARVATION_PASS mode="+(baseline?"baseline":"fixed")+" factoryInputs=450000 backgroundInputs=30000 auditInputs=7100 farmQueue="+farmQueue+" backgroundQueue="+backgroundQueue+" finalQueue="+finalQueue+" auditCuts="+auditCuts+" interactions="+interactions+" elapsedMs="+(System.nanoTime()-started)/1000000);
        } catch (Throwable e) { sender.sendMessage("STARVATION_FAIL "+e); throw new RuntimeException(e); }
        finally { Consumer.setPaused(false); }
    }
}

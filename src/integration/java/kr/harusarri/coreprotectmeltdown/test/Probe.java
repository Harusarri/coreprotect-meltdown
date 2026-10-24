package kr.harusarri.coreprotectmeltdown.test;

import java.util.*;
import java.util.concurrent.*;
import net.coreprotect.CoreProtect;
import net.coreprotect.command.*;
import net.coreprotect.consumer.*;
import net.coreprotect.model.entity.*;
import net.coreprotect.platform.PlatformSender;
import kr.harusarri.coreprotectmeltdown.*;

/** Test-only command, packaged separately and never installed into the requested server. */
public final class Probe implements Subcommand {
    public String name() { return "guard-test"; }
    public boolean canUse(PlatformSender sender,List<String> args) { return !sender.isPlayer() && !sender.isCommandBlock(); }
    static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public void execute(PlatformSender sender,String label,List<String> args) {
        var runtime=CoreProtect.getInstance().runtime();
        var consumer=runtime.consumer();
        Consumer.setPaused(true);
        long start=System.nanoTime();
        try(var lease=consumer.acquireMaintenance()) {
            check(consumer.pending()==0,"test requires empty DB queue");
            java.nio.file.Path configFile = java.nio.file.Files.createTempFile("meltdown-test", ".properties");
            java.nio.file.Files.writeString(configFile, "factory-queue-limit=16000\nlimit-player-events=true\n");
            GuardService.LIMITER.configure(GuardConfig.load(configFile));
            java.nio.file.Files.delete(configFile);
            long stamp=System.currentTimeMillis();
            String world="minecraft:overworld";
            int initial=consumer.pending();
            for(int i=0;i<500_000;i++) runtime.log(new LogEntry(stamp,"#piston","block_place",world,100,64,100,"type=minecraft:stone"));
            int blocks=consumer.pending()-initial;
            check(blocks>=4000 && blocks<6000,"regional block burst: "+blocks);
            initial=consumer.pending();
            for(int i=0;i<500_000;i++) runtime.logContainer(new ContainerLogEntry(stamp,"#hopper",i%2==0?"add":"remove",world,-101,64,-101,"minecraft:chest","minecraft:stone","",1,null,"source=automation"));
            int containers=consumer.pending()-initial;
            check(containers>=4000 && containers<6000,"regional container burst: "+containers);
            initial=consumer.pending();
            for(int i=0;i<100;i++) runtime.logEntitySpawn("#spawn",EntitySpawnData.log(new UUID(99,i+1),"minecraft:pig",new EntityLocation(world,300,64,300,0,0)));
            check(consumer.pending()==initial+100,"entity spawn ingress");
            initial=consumer.pending();
            for(int i=0;i<5000;i++) runtime.log(new LogEntry(stamp,"GuardTestPlayer","block_place",world,500,64,500,"type=minecraft:dirt"));
            check(consumer.pending()==initial+5000,"normal players remain logged");
            UUID uuid=new UUID(99,1);
            EntityLocation loc=new EntityLocation(world,300,64,300,0,0);
            for(int i=0;i<100_000;i++) runtime.updateEntityLocation(EntitySpawnData.location(uuid,loc,0));
            check(consumer.pending()==16000,"maintenance leaves player reserve");
            for(int i=0;i<4000;i++) runtime.log(new LogEntry(stamp,"GuardTestPlayer","block_place",world,500,64,500,"type=minecraft:dirt"));
            check(consumer.pending()==20000,"explicit player-limits opt-in caps player queue");
            runtime.log(new LogEntry(stamp,"GuardTestPlayer","block_place",world,700,64,700,"type=minecraft:stone"));
            runtime.logContainer(new ContainerLogEntry(stamp,"GuardTestPlayer","add",world,700,64,700,"minecraft:chest","minecraft:stone","",1));
            runtime.logEntitySpawn("#spawn",EntitySpawnData.log(new UUID(99,10001),"minecraft:pig",loc));
            runtime.logTrackedEntityKill(new LogEntry(stamp,"#factory","kill",world,700,64,700,"type=minecraft:pig"),uuid,loc);
            runtime.logEntityContainer("#hopper",new EntityContainerTransaction(uuid,loc,List.of(),List.of()));
            check(!runtime.logEntityInteraction("GuardTestPlayer",new EntityInteraction(uuid,"minecraft:pig",loc,loc,EntityInteractionAction.GENERIC,null)),"rejected interaction returns false");
            check(consumer.pending()==20000,"all seven ingress methods guarded");
            CommandRouter router=CoreProtectCommandBootstrap.createRouter();
            List<String> hidden=router.tabComplete(new DeniedSender(),List.of("d"));
            check(!hidden.contains("debug"),"no debug completion for unprivileged sender");
            check(!new DebugCommand().canUse(new DeniedSender(),List.of()),"debug permission enforced");
            check(router.tabComplete(sender,List.of("d")).contains("debug"),"debug completion for console");
            var s=GuardService.LIMITER.snapshot();
            check(s.cut()>1_000_000,"cut counts include every overload");
            var playerImpact=s.playerActors().get("GuardTestPlayer");
            check(playerImpact.admitted()==9000 && playerImpact.cut()==3 && playerImpact.attempted()==9003,"player impact matches actual runtime admission");
            check(playerImpact.reasons().get(Limiter.Reason.QUEUE_PRESSURE)==3,"player cuts caused by queue pressure");
            check(s.categories().values().stream().mapToLong(Limiter.Impact::cut).sum()==s.cut(),"impact totals conserved under actual mixins");
            check(s.hotspots().stream().anyMatch(h->h.x()==100 && h.z()==100),"positive heatmap center");
            check(s.hotspots().stream().anyMatch(h->h.x()==-101 && h.z()==-101),"negative heatmap center");
            GuardService.requestReport();
            sender.sendMessage("GUARD_TEST_PASS inputs=1109106 blocks="+blocks+" containers="+containers+" player=9000 queue="+consumer.pending()+" cut="+s.cut()+" elapsedMs="+(System.nanoTime()-start)/1_000_000);
        } catch(Exception e) { throw new RuntimeException(e); }
        finally { Consumer.setPaused(false); }
    }
    static final class DeniedSender implements PlatformSender {
        public String name(){return "Unprivileged";}
        public boolean hasPermission(String p){return false;}
        public void sendMessage(String s){}
        public boolean isPlayer(){return true;}
    }
}

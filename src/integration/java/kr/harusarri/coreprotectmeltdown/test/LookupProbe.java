package kr.harusarri.coreprotectmeltdown.test;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.coreprotect.CoreProtect;
import net.coreprotect.command.*;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.lookup.FabricLookupExecutor;
import net.coreprotect.platform.*;
import org.jdbi.v3.core.statement.*;
import kr.harusarri.coreprotectmeltdown.*;

/** Artificial SQL latency and failure injection. Isolated SQLite test server ONLY. */
public final class LookupProbe implements Subcommand {
    public String name() { return "meltdown-lookup-test"; }
    public boolean canUse(PlatformSender sender, List<String> args) { return !sender.isPlayer() && !sender.isCommandBlock(); }
    public void execute(PlatformSender console, String label, List<String> args) {
        boolean fail = args.contains("failure");
        boolean deadline = args.contains("deadline");
        Thread main = Thread.currentThread();
        var runtime = CoreProtect.getInstance().runtime();
        var config = runtime.database().jdbi().getConfig(SqlStatements.class);
        SqlLogger previous = config.getSqlLogger();
        AtomicInteger queries = new AtomicInteger(), responses = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        List<String> messages = new CopyOnWriteArrayList<>();
        config.setSqlLogger(new SqlLogger() {
            public void logBeforeExecution(StatementContext context) {
                if (Thread.currentThread().getName().equals("CoreProtect-Consumer")) return;
                try {
                    Probe.check(Thread.currentThread()!=main,"SQL must not run on main thread");
                    Probe.check(!Consumer.isPaused(),"lookup must not pause the writer");
                    Probe.check(context.getStatement().getQueryTimeout()>0 && context.getStatement().getQueryTimeout()<=10,"JDBC timeout applied before execution");
                    if (queries.getAndIncrement()==0) Thread.sleep(deadline ? 1500 : 2000);
                    if (fail) throw new IllegalStateException("injected database failure");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
                catch (Throwable e) { if (!fail) error.set(e); throw new RuntimeException(e); }
            }
        });
        PlatformSender player = new PlatformSender() {
            public String name() { return "NearProbe"; }
            public boolean hasPermission(String p) { return true; }
            public boolean isPlayer() { return true; }
            public String worldName() { return "minecraft:overworld"; }
            public PlatformLocation location() { return new PlatformLocation(worldName(),500,64,500); }
            public void sendMessage(String text) {
                if (Thread.currentThread()!=main) error.set(new AssertionError("response delivered off main thread"));
                responses.incrementAndGet(); messages.add(text);
                console.sendMessage("LOOKUP_PROBE_RESPONSE " + text);
            }
        };
        try {
            // Cache lookup must never borrow a JDBC connection, including unknown dimensions.
            Class<?> support = Class.forName("net.coreprotect.event.FabricEventSupport");
            Method world = support.getDeclaredMethod("worldId", runtime.getClass(), String.class); world.setAccessible(true);
            int missing = (Integer)world.invoke(null,runtime,"meltdown:missing-world");
            Probe.check(missing==0 && queries.get()==0,"missing placement-cache world uses no SQL");
            var executor = new FabricLookupExecutor();
            long started = System.nanoTime();
            new NearSubcommand(executor).execute(player,"co",List.of());
            long millis = (System.nanoTime()-started)/1_000_000;
            Probe.check(millis<500,"near command returned promptly: "+millis);
            // Same identity and different identity floods must be rejected rather than queued.
            for(int i=0;i<20;i++) new NearSubcommand(executor).execute(player,"co",List.of("t:1h"));
            console.sendMessage("LOOKUP_PROBE_DISPATCH returnedMillis="+millis+" consumerPaused="+Consumer.isPaused());
            var timer = Executors.newSingleThreadScheduledExecutor(r -> {Thread t=new Thread(r,"Lookup-Probe-Wait");t.setDaemon(true);return t;});
            AtomicInteger attempts=new AtomicInteger();
            timer.scheduleAtFixedRate(() -> LookupRunner.serverExecutor().execute(() -> {
                if (LookupRunner.status().contains("lookupActive=true") && attempts.incrementAndGet()<150) return;
                timer.shutdown();config.setSqlLogger(previous);
                try {
                    if(error.get()!=null) throw new AssertionError(error.get());
                    Probe.check(!LookupRunner.status().contains("lookupActive=true"),"lookup finishes within test bound");
                    Probe.check(queries.get()>0 && responses.get()>0,"actual SQL and responses observed");
                    Probe.check(!Consumer.isPaused(),"consumer remains running after completion");
                    if(fail || deadline) Probe.check(messages.stream().anyMatch(m->m.contains("중단") || m.contains("failed") || m.contains("Failed")),"failure delivered to requester");
                    console.sendMessage("LOOKUP_PROBE_PASS mode="+(fail?"failure":deadline?"deadline":"slow")+" queries="+queries+" responses="+responses+" "+LookupRunner.status());
                } catch(Throwable e) {console.sendMessage("LOOKUP_PROBE_FAIL "+e);}
            }),100,100,TimeUnit.MILLISECONDS);
        } catch(Exception e) {config.setSqlLogger(previous);throw new RuntimeException(e);}
    }
}

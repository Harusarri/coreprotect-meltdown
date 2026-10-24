package kr.harusarri.coreprotectmeltdown.test;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import net.coreprotect.CoreProtect;
import net.coreprotect.command.*;
import net.coreprotect.consumer.*;
import net.coreprotect.lookup.*;
import net.coreprotect.platform.*;
import net.coreprotect.util.FabricItemData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import kr.harusarri.coreprotectmeltdown.LookupRunner;

/** Exercises the actual 1.3 parser, database, renderer and transformed asynchronous lookup path. */
public final class CompatibilityProbe implements Subcommand {
    public String name() { return "compatibility-test"; }
    public boolean canUse(PlatformSender s, List<String> a) { return !s.isPlayer() && !s.isCommandBlock(); }
    public void execute(PlatformSender console, String label, List<String> args) {
        new Run(console).start();
    }
    static final class Run {
        final PlatformSender console;
        final Thread main = Thread.currentThread();
        final FabricLookupExecutor executor = new FabricLookupExecutor();
        final String actor = "Compat" + System.currentTimeMillis();
        final List<String> output = new ArrayList<>();
        int step, polls;
        boolean allowGive;
        final PlatformSender sender = new PlatformSender() {
            public String name() { return "CompatRequester"; }
            public boolean isPlayer() { return true; }
            public String worldName() { return "minecraft:overworld"; }
            public PlatformLocation location() { return new PlatformLocation(worldName(), 500, 64, 500); }
            public boolean hasPermission(String p) {
                Probe.check(Thread.currentThread() == main, "permission queried off server thread");
                return !p.equals("coreprotect.give") || allowGive;
            }
            public boolean hasPermission(String p, boolean def) { return hasPermission(p); }
            public void sendMessage(String s) {
                Probe.check(Thread.currentThread() == main, "response off server thread");
                output.add(s);
                console.sendMessage("COMPAT_RESPONSE step=" + step + " " + s);
            }
        };
        Run(PlatformSender console) { this.console = console; }
        void later(Runnable r) {
            CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() ->
                LookupRunner.serverExecutor().execute(() -> {
                    try { r.run(); } catch (Throwable t) { console.sendMessage("COMPAT_FAIL step=" + step + " " + t); }
                }));
        }
        void start() {
            var runtime = CoreProtect.getInstance().runtime();
            ItemStack stack = new ItemStack(Items.DIAMOND, 37);
            stack.set(DataComponents.CUSTOM_NAME, Component.literal("MeltdownCompatibilityGem"));
            String data = FabricItemData.serialize(stack);
            for (int i = 0; i < 12; i++) runtime.logContainer(new ContainerLogEntry(
                System.currentTimeMillis(), actor, "add", "minecraft:overworld", 500+i, 64, 500,
                "minecraft:chest", "minecraft:diamond", data, 37));
            later(this::waitSeed);
        }
        void waitSeed() {
            Probe.check(++polls < 300, "seed queue drains");
            if (CoreProtect.getInstance().runtime().consumer().pending() != 0) { later(this::waitSeed); return; }
            polls = 0;
            later(this::dispatch);
        }
        void dispatch() {
            output.clear();
            allowGive = step != 1;
            List<String> q = new ArrayList<>(List.of("u:" + actor, "a:container", "r:32", "t:1h"));
            switch (step) {
                case 0, 1 -> {}
                case 2 -> q.add("#count");
                case 3 -> q.add("#sum");
                case 4 -> q.add("count");
                case 5 -> q.add("sum");
                case 6 -> q.add("#summary");
                case 7 -> q = new ArrayList<>(List.of("u:"+actor, "a:container", "r:32"));
                case 8 -> q = new ArrayList<>(List.of("2"));
                case 9 -> q.add("i:minecraft:dirt");
                default -> { console.sendMessage("COMPAT_PASS cases=10 permissionAllowDeny=true countAliases=4 summary=true radiusWithoutTime=true pagination=true zeroResults=true quantity=37 customName=true"); return; }
            }
            executor.execute(sender, "co", q);
            later(this::complete);
        }
        void complete() {
            Probe.check(++polls < 300, "lookup completes");
            if (LookupRunner.status().contains("lookupActive=true")) { later(this::complete); return; }
            polls = 0;
            String all = String.join("\n", output).replaceAll("§.", "");
            Probe.check(!all.contains("failed") && !all.contains("중단") && !all.contains("No permission"), "successful lookup: " + all);
            if (step == 0) {
                Matcher m = Pattern.compile("/co give #(\\d+)").matcher(all);
                Probe.check(m.find(), "authorized recovery link present");
                ItemStack restored = LookupItemStore.get(Long.parseLong(m.group(1)));
                Probe.check(restored != null && restored.getCount() == 37 && restored.is(Items.DIAMOND), "recovery quantity/type");
                Probe.check(restored.get(DataComponents.CUSTOM_NAME).getString().equals("MeltdownCompatibilityGem"), "recovery component retained");
                Probe.check(all.contains("MeltdownCompatibilityGem"), "custom-name tooltip");
            } else if (step == 1) {
                Probe.check(all.contains("Compat") && !all.contains("/co give #"), "denied recovery link absent with results");
            } else if (step >= 2 && step <= 5) {
                Probe.check(all.contains("12") && all.toLowerCase(Locale.ROOT).contains("rows"), "raw count is 12: " + all);
                Probe.check(!all.contains("/co give #"), "count has no item links");
            } else if (step == 6) {
                Probe.check(all.contains(actor) && all.contains("444"), "summary groups 12 x 37 items: " + all);
            } else if (step == 7 || step == 8) {
                Probe.check(all.contains("/co give #"), "bounded/paged query returns items");
                if (step == 8) Probe.check(all.contains("Page 2/3"), "second page selected: " + all);
            } else if (step == 9) {
                Probe.check(all.toLowerCase(Locale.ROOT).contains("no results"), "zero result query: " + all);
            }
            console.sendMessage("COMPAT_CASE_PASS " + step);
            step++;
            later(this::dispatch);
        }
    }
}

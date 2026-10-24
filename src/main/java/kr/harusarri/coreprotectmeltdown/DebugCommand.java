package kr.harusarri.coreprotectmeltdown;

import java.util.*;
import java.util.stream.Collectors;
import net.coreprotect.command.Subcommand;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.platform.ComponentChat;
import net.coreprotect.platform.PlatformLocation;
import net.coreprotect.platform.PlatformSender;

public final class DebugCommand implements Subcommand {
    private static final String PREFIX = "§8[§3CP§bMeltdown§8] §r";

    public String name() { return "debug"; }
    public String permission() { return "coreprotect.debug"; }
    public boolean canUse(PlatformSender sender, List<String> args) { return permitted(sender); }

    public static boolean hasMeltdownAdmin(PlatformSender sender) {
        return sender.hasPermission("coreprotect.meltdown.admin", false)
            || sender.hasPermission("coreprotectmeltdown.admin", false);
    }

    public static boolean permitted(PlatformSender sender) {
        return sender.hasPermission("coreprotect.debug", false)
            || sender.hasPermission("coreprotect.*", false)
            || hasMeltdownAdmin(sender);
    }

    public void execute(PlatformSender sender, String label, List<String> args) {
        String lang = MeltdownI18n.resolveLang(sender);
        if (!permitted(sender)) {
            sender.sendMessage(PREFIX + MeltdownI18n.trByLang(lang, "cmd.no_permission", "coreprotect.debug"));
            return;
        }
        String command = args.isEmpty() ? "status" : args.getFirst().toLowerCase(Locale.ROOT);
        switch (command) {
            case "status" -> {
                sendFormattedStatus(sender, lang);
                heatmap(sender, 5, true, lang);
                if (hasMeltdownAdmin(sender)) {
                    sender.sendComponent("  "
                        + ComponentChat.command("/co debug status", MeltdownI18n.getByLang(lang, "cmd.btn.refresh")) + "  "
                        + ComponentChat.command("/co debug heatmap", MeltdownI18n.getByLang(lang, "cmd.btn.heatmap_all")) + "  "
                        + ComponentChat.command("/co debug heatmap test", MeltdownI18n.getByLang(lang, "cmd.btn.heatmap_test")) + "  "
                        + ComponentChat.command("/co debug config", MeltdownI18n.getByLang(lang, "cmd.btn.config")) + "  "
                        + ComponentChat.command("/co debug reload", MeltdownI18n.getByLang(lang, "cmd.btn.reload")));
                }
                GuardService.requestReport();
            }
            case "heatmap" -> {
                int limit = 10;
                if (args.size() > 1) {
                    if (args.get(1).equalsIgnoreCase("test")) {
                        if (!hasMeltdownAdmin(sender)) {
                            sender.sendMessage(PREFIX + MeltdownI18n.trByLang(lang, "cmd.no_permission", "coreprotect.meltdown.admin"));
                            return;
                        }
                        PlatformLocation loc = sender.location();
                        String world = loc != null && loc.worldName() != null ? loc.worldName() : sender.worldName();
                        int x = loc != null ? loc.x() : 0;
                        int y = loc != null ? loc.y() : 80;
                        int z = loc != null ? loc.z() : 0;
                        GuardService.LIMITER.recordTestHotspot(world, x, y, z);
                        sender.sendMessage(PREFIX + MeltdownI18n.trByLang(lang, "cmd.heatmap.test_recorded",
                            formatWorld(world, lang), x, y, z));
                    } else {
                        try { limit = Integer.parseInt(args.get(1)); }
                        catch (NumberFormatException e) {
                            sender.sendMessage(PREFIX + MeltdownI18n.getByLang(lang, "cmd.heatmap.usage"));
                            return;
                        }
                    }
                }
                if (limit < 1 || limit > 50) {
                    sender.sendMessage(PREFIX + MeltdownI18n.getByLang(lang, "cmd.heatmap.limit_range"));
                    return;
                }
                heatmap(sender, limit, false, lang);
            }
            case "tp", "teleport" -> {
                if (!hasMeltdownAdmin(sender)) {
                    sender.sendMessage(PREFIX + MeltdownI18n.trByLang(lang, "cmd.no_permission", "coreprotect.meltdown.admin"));
                    return;
                }
                if (!sender.isPlayer()) {
                    sender.sendMessage(PREFIX + MeltdownI18n.getByLang(lang, "cmd.tp.player_only"));
                    return;
                }
                if (args.size() < 5) {
                    sender.sendMessage(PREFIX + MeltdownI18n.getByLang(lang, "cmd.tp.usage"));
                    return;
                }
                String world = args.get(1);
                double x, y, z;
                try {
                    x = Double.parseDouble(args.get(2));
                    y = Double.parseDouble(args.get(3));
                    z = Double.parseDouble(args.get(4));
                } catch (NumberFormatException e) {
                    sender.sendMessage(PREFIX + MeltdownI18n.getByLang(lang, "cmd.tp.invalid_coords"));
                    return;
                }
                LookupRunner.serverExecutor().execute(() -> {
                    String normalizedWorld = world.contains(":") ? world : "minecraft:" + world;
                    boolean ok = sender.teleport(world, x, y, z) || sender.teleport(normalizedWorld, x, y, z);
                    if (ok) {
                        sender.sendMessage(PREFIX + MeltdownI18n.trByLang(lang, "cmd.tp.success",
                            formatWorld(world, lang), x, y, z));
                    } else {
                        sender.sendMessage(PREFIX + MeltdownI18n.trByLang(lang, "cmd.tp.world_not_found", EventInfo.safe(world)));
                    }
                });
            }
            case "write" -> sender.sendMessage(GuardService.requestReport()
                ? PREFIX + MeltdownI18n.getByLang(lang, "cmd.write.requested")
                : PREFIX + MeltdownI18n.getByLang(lang, "cmd.write.in_progress"));
            case "reload" -> {
                try {
                    GuardService.reload();
                    GuardConfig c = GuardService.LIMITER.config();
                    sender.sendMessage(PREFIX + MeltdownI18n.trByLang(lang, "cmd.reload.success",
                        c.enabled() ? "§aON" : "§cOFF", c.queueSoftLimit(), c.queueHardLimit(), c.regionPerSecond()));
                    if (!c.enabled()) {
                        sender.sendMessage(PREFIX + MeltdownI18n.getByLang(lang, "cmd.reload.warn_disabled"));
                    }
                } catch (Exception e) {
                    sender.sendMessage(PREFIX + MeltdownI18n.trByLang(lang, "cmd.reload.failed", EventInfo.safe(e.getMessage())));
                }
            }
            case "config" -> {
                var values = GuardService.LIMITER.config().values();
                sender.sendMessage(MeltdownI18n.getByLang(lang, "cmd.config.header"));
                if (args.size() > 1) {
                    String key = args.get(1);
                    sender.sendMessage(values.containsKey(key)
                        ? " §7▪ §b" + key + " §8= §f" + values.get(key)
                        : PREFIX + MeltdownI18n.trByLang(lang, "cmd.config.unknown_key", EventInfo.safe(key)));
                } else {
                    values.forEach((key, value) -> sender.sendMessage(" §7▪ §b" + key + " §8= §f" + value));
                }
            }
            case "lang", "language" -> {
                if (args.size() < 2) {
                    sender.sendMessage(PREFIX + MeltdownI18n.trByLang(lang, "cmd.lang.set",
                        MeltdownI18n.mode() + " (" + lang + ")"));
                    return;
                }
                if (!MeltdownI18n.setMode(args.get(1))) {
                    sender.sendMessage(PREFIX + MeltdownI18n.getByLang(lang, "cmd.lang.usage"));
                    return;
                }
                String updatedLang = MeltdownI18n.resolveLang(sender);
                sender.sendMessage(PREFIX + MeltdownI18n.trByLang(updatedLang, "cmd.lang.set",
                    MeltdownI18n.mode() + " (" + updatedLang + ")"));
            }
            default -> sender.sendMessage(PREFIX + MeltdownI18n.trByLang(lang, "cmd.usage", label));
        }
    }

    private static void sendFormattedStatus(PlatformSender sender, String lang) {
        Limiter.Snapshot s = GuardService.LIMITER.snapshot();
        int pending = Math.max(0, GuardService.pending());
        int heap = GuardService.heapPercent();
        boolean pressure = GuardService.heapPressure();
        String heapColor = pressure ? "§c" : (heap >= 75 ? "§e" : "§a");
        var playerImpact = s.categories().get(Limiter.Category.PLAYER_ATTRIBUTED);

        sender.sendMessage(MeltdownI18n.getByLang(lang, "status.header"));
        sender.sendMessage(MeltdownI18n.trByLang(lang, "status.line_protection",
            s.config().enabled() ? "§a§lON" : "§c§lOFF",
            pending, s.config().queueSoftLimit(), s.config().queueHardLimit(), s.peakPending(),
            Consumer.isPaused() ? MeltdownI18n.getByLang(lang, "status.paused_tag") : ""));
        sender.sendMessage(MeltdownI18n.trByLang(lang, "status.line_heap",
            heapColor, heap,
            pressure ? MeltdownI18n.getByLang(lang, "status.heap_circuit") : MeltdownI18n.getByLang(lang, "status.heap_ok"),
            s.admitted(),
            s.cut() > 0 ? MeltdownI18n.trByLang(lang, "status.count_cut", s.cut()) : MeltdownI18n.getByLang(lang, "status.count_zero")));
        sender.sendMessage(MeltdownI18n.trByLang(lang, "status.line_player",
            playerImpact.admitted(),
            playerImpact.cut() > 0
                ? MeltdownI18n.trByLang(lang, "status.player_cut", playerImpact.cut(), playerImpact.cutPercent())
                : MeltdownI18n.getByLang(lang, "status.player_preserved")));
        sender.sendMessage(MeltdownI18n.trByLang(lang, "status.line_factory",
            s.factoryAdmitted(),
            s.factoryCut() > 0 ? MeltdownI18n.trByLang(lang, "status.count_cut", s.factoryCut()) : MeltdownI18n.getByLang(lang, "status.count_zero"),
            s.factoryCells()));
        if (!s.reasons().isEmpty()) {
            String reasonSummary = s.reasons().entrySet().stream()
                .map(e -> MeltdownI18n.trByLang(lang, "status.reason_entry", formatReason(e.getKey(), lang), e.getValue()))
                .collect(Collectors.joining("§8, "));
            sender.sendMessage(MeltdownI18n.trByLang(lang, "status.line_reasons", reasonSummary));
        }
        sender.sendMessage(MeltdownI18n.trByLang(lang, "status.line_lookup",
            LookupRunner.isActive() ? MeltdownI18n.getByLang(lang, "status.lookup_active") : MeltdownI18n.getByLang(lang, "status.lookup_idle"),
            LookupRunner.completedCount(),
            LookupRunner.failedCount() > 0 ? "§c" + LookupRunner.failedCount() : "§70",
            LookupRunner.lastMillis()));
    }

    private static void heatmap(PlatformSender sender, int limit, boolean inline, String lang) {
        var hotspots = GuardService.LIMITER.snapshot().hotspots();
        boolean admin = hasMeltdownAdmin(sender);
        if (!inline) {
            sender.sendMessage(MeltdownI18n.getByLang(lang, "heatmap.header"));
        }
        if (hotspots.isEmpty()) {
            sender.sendMessage(MeltdownI18n.getByLang(lang, "heatmap.empty"));
            if (admin && !inline) {
                sender.sendComponent("   " + ComponentChat.command("/co debug heatmap test", MeltdownI18n.getByLang(lang, "heatmap.btn_create_test")));
            }
        } else {
            int shown = Math.min(limit, hotspots.size());
            sender.sendMessage(MeltdownI18n.trByLang(lang, "heatmap.summary",
                shown, hotspots.size(), admin ? MeltdownI18n.getByLang(lang, "heatmap.click_hint") : ""));
            for (int i = 0; i < shown; i++) {
                Limiter.Hotspot h = hotspots.get(i);
                String topReason = h.reasons().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(e -> formatReason(e.getKey(), lang))
                    .orElse(MeltdownI18n.getByLang(lang, "heatmap.default_reason"));
                String line = MeltdownI18n.trByLang(lang, "heatmap.row",
                    i + 1, formatWorld(h.cell().world(), lang), h.x(), h.y(), h.z(), h.cuts(), topReason);
                if (admin) {
                    String tpCmd = String.format(Locale.ROOT, "/co debug tp %s %.1f %.1f %.1f", h.cell().world(), h.x(), h.y(), h.z());
                    sender.sendComponent(ComponentChat.command(tpCmd, line + MeltdownI18n.getByLang(lang, "heatmap.btn_tp")));
                } else {
                    sender.sendMessage(line);
                }
            }
        }
    }

    private static String formatWorld(String world, String lang) {
        if (world == null || world.isBlank()) return MeltdownI18n.getByLang(lang, "world.unknown");
        String w = world.toLowerCase(Locale.ROOT);
        if (w.equals("minecraft:overworld") || w.equals("overworld")) return MeltdownI18n.getByLang(lang, "world.overworld");
        if (w.equals("minecraft:the_nether") || w.equals("the_nether") || w.equals("nether")) return MeltdownI18n.getByLang(lang, "world.the_nether");
        if (w.equals("minecraft:the_end") || w.equals("the_end") || w.equals("end")) return MeltdownI18n.getByLang(lang, "world.the_end");
        return "§b" + EventInfo.safe(world);
    }

    private static String formatReason(Limiter.Reason reason, String lang) {
        if (reason == null) return MeltdownI18n.getByLang(lang, "reason.other");
        return switch (reason) {
            case REGION_RATE -> MeltdownI18n.getByLang(lang, "reason.region_rate");
            case GLOBAL_RATE -> MeltdownI18n.getByLang(lang, "reason.global_rate");
            case QUEUE_PRESSURE -> MeltdownI18n.getByLang(lang, "reason.queue_pressure");
            case FACTORY_BACKLOG -> MeltdownI18n.getByLang(lang, "reason.factory_backlog");
            case HEAP_PRESSURE -> MeltdownI18n.getByLang(lang, "reason.heap_pressure");
            case PAYLOAD_TOO_LARGE -> MeltdownI18n.getByLang(lang, "reason.payload_too_large");
            case TRACKING_CAPACITY -> MeltdownI18n.getByLang(lang, "reason.tracking_capacity");
        };
    }

    public List<String> tabComplete(PlatformSender sender, String label, List<String> args) {
        if (args.size() == 2 && args.getFirst().equalsIgnoreCase("config"))
            return GuardService.LIMITER.config().values().keySet().stream().filter(s -> s.startsWith(args.get(1).toLowerCase(Locale.ROOT))).toList();
        if (args.size() == 2 && args.getFirst().equalsIgnoreCase("heatmap") && hasMeltdownAdmin(sender))
            return List.of("10", "20", "50", "test").stream().filter(s -> s.startsWith(args.get(1).toLowerCase(Locale.ROOT))).toList();
        if (args.size() == 2 && (args.getFirst().equalsIgnoreCase("lang") || args.getFirst().equalsIgnoreCase("language")))
            return List.of("auto", "en_us", "ko_kr").stream().filter(s -> s.startsWith(args.get(1).toLowerCase(Locale.ROOT))).toList();
        if (args.size() > 1) return List.of();
        String prefix = args.isEmpty() ? "" : args.getFirst().toLowerCase(Locale.ROOT);
        List<String> subs = hasMeltdownAdmin(sender)
            ? List.of("status", "heatmap", "tp", "write", "config", "lang", "reload")
            : List.of("status", "heatmap", "write", "config", "lang", "reload");
        return subs.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}

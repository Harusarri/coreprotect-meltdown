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
        if (!permitted(sender)) {
            sender.sendMessage(PREFIX + "§c권한이 부족합니다. §7(필요 권한: §fcoreprotect.debug§7)");
            return;
        }
        String command = args.isEmpty() ? "status" : args.getFirst().toLowerCase(Locale.ROOT);
        switch (command) {
            case "status" -> {
                sendFormattedStatus(sender);
                heatmap(sender, 5, true);
                if (hasMeltdownAdmin(sender)) {
                    sender.sendComponent("  "
                        + ComponentChat.command("/co debug status", "§b[새로고침]") + "  "
                        + ComponentChat.command("/co debug heatmap", "§6[히트맵 전체]") + "  "
                        + ComponentChat.command("/co debug heatmap test", "§e[테스트 생성]") + "  "
                        + ComponentChat.command("/co debug config", "§7[설정 보기]") + "  "
                        + ComponentChat.command("/co debug reload", "§a[리로드]"));
                }
                GuardService.requestReport();
            }
            case "heatmap" -> {
                int limit = 10;
                if (args.size() > 1) {
                    if (args.get(1).equalsIgnoreCase("test")) {
                        if (!hasMeltdownAdmin(sender)) {
                            sender.sendMessage(PREFIX + "§c권한이 부족합니다. §7(필요 권한: §fcoreprotect.meltdown.admin§7)");
                            return;
                        }
                        PlatformLocation loc = sender.location();
                        String world = loc != null && loc.worldName() != null ? loc.worldName() : sender.worldName();
                        int x = loc != null ? loc.x() : 0;
                        int y = loc != null ? loc.y() : 80;
                        int z = loc != null ? loc.z() : 0;
                        GuardService.LIMITER.recordTestHotspot(world, x, y, z);
                        sender.sendMessage(String.format(Locale.ROOT,
                            PREFIX + "§a테스트 히트맵 좌표가 기록되었습니다: %s §f(%d, %d, %d)",
                            formatWorld(world), x, y, z));
                    } else {
                        try { limit = Integer.parseInt(args.get(1)); }
                        catch (NumberFormatException e) {
                            sender.sendMessage(PREFIX + "§e사용법: §f/co debug heatmap [1..50|test]");
                            return;
                        }
                    }
                }
                if (limit < 1 || limit > 50) {
                    sender.sendMessage(PREFIX + "§c표시 개수는 1~50 사이여야 합니다.");
                    return;
                }
                heatmap(sender, limit, false);
            }
            case "tp", "teleport" -> {
                if (!hasMeltdownAdmin(sender)) {
                    sender.sendMessage(PREFIX + "§c권한이 부족합니다. §7(필요 권한: §fcoreprotect.meltdown.admin§7)");
                    return;
                }
                if (!sender.isPlayer()) {
                    sender.sendMessage(PREFIX + "§c플레이어만 사용할 수 있는 명령어입니다.");
                    return;
                }
                if (args.size() < 5) {
                    sender.sendMessage(PREFIX + "§e사용법: §f/co debug tp <월드> <x> <y> <z>");
                    return;
                }
                String world = args.get(1);
                double x, y, z;
                try {
                    x = Double.parseDouble(args.get(2));
                    y = Double.parseDouble(args.get(3));
                    z = Double.parseDouble(args.get(4));
                } catch (NumberFormatException e) {
                    sender.sendMessage(PREFIX + "§c올바르지 않은 좌표입니다.");
                    return;
                }
                LookupRunner.serverExecutor().execute(() -> {
                    String normalizedWorld = world.contains(":") ? world : "minecraft:" + world;
                    boolean ok = sender.teleport(world, x, y, z) || sender.teleport(normalizedWorld, x, y, z);
                    if (ok) {
                        sender.sendMessage(String.format(Locale.ROOT,
                            PREFIX + "§a히트맵 위치로 이동했습니다: %s §f(%.1f, %.1f, %.1f)",
                            formatWorld(world), x, y, z));
                    } else {
                        sender.sendMessage(PREFIX + "§c월드(§f" + EventInfo.safe(world) + "§c)를 찾을 수 없어 이동에 실패했습니다.");
                    }
                });
            }
            case "write" -> sender.sendMessage(GuardService.requestReport()
                ? PREFIX + "§a진단 리포트(§fdebug.txt§a) 저장을 요청했습니다."
                : PREFIX + "§e이미 리포트 저장 작업이 진행 중입니다.");
            case "reload" -> {
                try {
                    GuardService.reload();
                    GuardConfig c = GuardService.LIMITER.config();
                    sender.sendMessage(String.format(Locale.ROOT,
                        PREFIX + "§a설정을 다시 불러왔습니다. §8(§7보호: %s§8, §7대기열: §f%,d§7/§f%,d§8, §7구역제한: §f%,d/s§8)",
                        c.enabled() ? "§aON" : "§cOFF", c.queueSoftLimit(), c.queueHardLimit(), c.regionPerSecond()));
                    if (!c.enabled()) {
                        sender.sendMessage(PREFIX + "§e주의: 보호 기능이 비활성화되어 모든 이벤트가 제한 없이 기록됩니다.");
                    }
                } catch (Exception e) {
                    sender.sendMessage(PREFIX + "§c리로드 실패 (기존 설정 유지): §f" + EventInfo.safe(e.getMessage()));
                }
            }
            case "config" -> {
                var values = GuardService.LIMITER.config().values();
                sender.sendMessage("§8----- §3CoreProtect §bMeltdown §8| §f현재 설정 §8-----");
                if (args.size() > 1) {
                    String key = args.get(1);
                    sender.sendMessage(values.containsKey(key)
                        ? " §7▪ §b" + key + " §8= §f" + values.get(key)
                        : PREFIX + "§c알 수 없는 설정 키입니다: §f" + EventInfo.safe(key));
                } else {
                    values.forEach((key, value) -> sender.sendMessage(" §7▪ §b" + key + " §8= §f" + value));
                }
            }
            default -> sender.sendMessage(PREFIX + "§e사용법: §f/" + label + " debug §7[status | heatmap [1..50|test] | tp <월드> <x> <y> <z> | write | config [키] | reload]");
        }
    }

    private static void sendFormattedStatus(PlatformSender sender) {
        Limiter.Snapshot s = GuardService.LIMITER.snapshot();
        int pending = Math.max(0, GuardService.pending());
        int heap = GuardService.heapPercent();
        boolean pressure = GuardService.heapPressure();
        String heapColor = pressure ? "§c" : (heap >= 75 ? "§e" : "§a");
        var playerImpact = s.categories().get(Limiter.Category.PLAYER_ATTRIBUTED);

        sender.sendMessage("§8----- §3CoreProtect §bMeltdown §8| §f보호 상태 §8-----");
        sender.sendMessage(String.format(Locale.ROOT,
            " §7▪ 보호 상태: %s §8| §7대기열: §f%,d§7/§f%,d §8(최대 %,d, 피크 §f%,d§8)%s",
            s.config().enabled() ? "§a§lON" : "§c§lOFF",
            pending, s.config().queueSoftLimit(), s.config().queueHardLimit(), s.peakPending(),
            Consumer.isPaused() ? " §c[일시정지]" : ""));
        sender.sendMessage(String.format(Locale.ROOT,
            " §7▪ 힙 메모리: %s%d%%%s §8| §7누적 허용: §a%,d건 §8| §7누적 차단: %s",
            heapColor, heap, pressure ? " §c(회로 차단)" : " §8(정상)",
            s.admitted(), s.cut() > 0 ? String.format(Locale.ROOT, "§e%,d건", s.cut()) : "§70건"));
        sender.sendMessage(String.format(Locale.ROOT,
            " §7▪ 플레이어 기록: §a허용 %,d건 §8| §7차단 %s",
            playerImpact.admitted(),
            playerImpact.cut() > 0
                ? String.format(Locale.ROOT, "§c%,d건 §8(%.1f%%)", playerImpact.cut(), playerImpact.cutPercent())
                : "§70건 §8(보존됨)"));
        sender.sendMessage(String.format(Locale.ROOT,
            " §7▪ 공장 / 자동화: §a허용 %,d건 §8| §7차단 %s §8(활성 구역 §f%d곳§8)",
            s.factoryAdmitted(),
            s.factoryCut() > 0 ? String.format(Locale.ROOT, "§e%,d건", s.factoryCut()) : "§70건",
            s.factoryCells()));
        if (!s.reasons().isEmpty()) {
            String reasonSummary = s.reasons().entrySet().stream()
                .map(e -> "§f" + formatReason(e.getKey()) + " §e" + String.format(Locale.ROOT, "%,d건", e.getValue()))
                .collect(Collectors.joining("§8, "));
            sender.sendMessage(" §7▪ 차단 사유: " + reasonSummary);
        }
        sender.sendMessage(String.format(Locale.ROOT,
            " §7▪ 조회 엔진: %s §8| §7성공: §a%,d §8| §7실패: %s §8| §7최근 소요: §b%dms",
            LookupRunner.isActive() ? "§e조회 중" : "§a대기 중",
            LookupRunner.completedCount(),
            LookupRunner.failedCount() > 0 ? "§c" + LookupRunner.failedCount() : "§70",
            LookupRunner.lastMillis()));
    }

    private static void heatmap(PlatformSender sender, int limit, boolean inline) {
        var hotspots = GuardService.LIMITER.snapshot().hotspots();
        boolean admin = hasMeltdownAdmin(sender);
        if (!inline) {
            sender.sendMessage("§8----- §3CoreProtect §bMeltdown §8| §f과부하 히트맵 §8-----");
        }
        if (hotspots.isEmpty()) {
            sender.sendMessage(" §7▪ 차단된 과부하 구역(히트맵): §8없음");
            if (admin && !inline) {
                sender.sendComponent("   " + ComponentChat.command("/co debug heatmap test", "§e[클릭하여 현재 위치에 테스트 히트맵 생성]"));
            }
        } else {
            int shown = Math.min(limit, hotspots.size());
            sender.sendMessage(String.format(Locale.ROOT,
                " §7▪ 과부하 차단 중심점 §8(상위 %d곳 / 전체 %d곳%s):",
                shown, hotspots.size(), admin ? ", 클릭 시 텔레포트" : ""));
            for (int i = 0; i < shown; i++) {
                Limiter.Hotspot h = hotspots.get(i);
                String topReason = h.reasons().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(e -> formatReason(e.getKey()))
                    .orElse("속도 제한");
                String line = String.format(Locale.ROOT,
                    "  §8#%d %s §f(%.0f, %.0f, %.0f) §8- §e차단 %,d건 §8[§7%s§8]",
                    i + 1, formatWorld(h.cell().world()), h.x(), h.y(), h.z(), h.cuts(), topReason);
                if (admin) {
                    String tpCmd = String.format(Locale.ROOT, "/co debug tp %s %.1f %.1f %.1f", h.cell().world(), h.x(), h.y(), h.z());
                    sender.sendComponent(ComponentChat.command(tpCmd, line + " §a§l[텔레포트]"));
                } else {
                    sender.sendMessage(line);
                }
            }
        }
    }

    private static String formatWorld(String world) {
        if (world == null || world.isBlank()) return "§7알 수 없음";
        String w = world.toLowerCase(Locale.ROOT);
        if (w.equals("minecraft:overworld") || w.equals("overworld")) return "§a오버월드";
        if (w.equals("minecraft:the_nether") || w.equals("the_nether") || w.equals("nether")) return "§c네더";
        if (w.equals("minecraft:the_end") || w.equals("the_end") || w.equals("end")) return "§d엔드";
        return "§b" + EventInfo.safe(world);
    }

    private static String formatReason(Limiter.Reason reason) {
        if (reason == null) return "기타";
        return switch (reason) {
            case REGION_RATE -> "구역 속도초과";
            case GLOBAL_RATE -> "전역 속도초과";
            case QUEUE_PRESSURE -> "대기열 포화";
            case FACTORY_BACKLOG -> "공장 대기열초과";
            case HEAP_PRESSURE -> "메모리 보호";
            case PAYLOAD_TOO_LARGE -> "데이터 크기초과";
            case TRACKING_CAPACITY -> "추적 구역초과";
        };
    }

    public List<String> tabComplete(PlatformSender sender, String label, List<String> args) {
        if (args.size() == 2 && args.getFirst().equalsIgnoreCase("config"))
            return GuardService.LIMITER.config().values().keySet().stream().filter(s -> s.startsWith(args.get(1).toLowerCase(Locale.ROOT))).toList();
        if (args.size() == 2 && args.getFirst().equalsIgnoreCase("heatmap") && hasMeltdownAdmin(sender))
            return List.of("10", "20", "50", "test").stream().filter(s -> s.startsWith(args.get(1).toLowerCase(Locale.ROOT))).toList();
        if (args.size() > 1) return List.of();
        String prefix = args.isEmpty() ? "" : args.getFirst().toLowerCase(Locale.ROOT);
        List<String> subs = hasMeltdownAdmin(sender)
            ? List.of("status", "heatmap", "tp", "write", "config", "reload")
            : List.of("status", "heatmap", "write", "config", "reload");
        return subs.stream().filter(s -> s.startsWith(prefix)).toList();
    }
}

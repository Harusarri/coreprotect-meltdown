package kr.harusarri.coreprotectmeltdown;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.coreprotect.CoreProtect;
import net.coreprotect.platform.PlatformSender;

/**
 * Server-side localization loader and resolver for CoreProtect Meltdown.
 * Loads standard Minecraft language files from {@code /assets/coreprotect_meltdown/lang/en_us.json}
 * and {@code /assets/coreprotect_meltdown/lang/ko_kr.json} and automatically resolves player
 * client locale ({@code ko_*} -> Korean, otherwise English) or explicit admin override.
 */
public final class MeltdownI18n {
    public static final String EN_US = "en_us";
    public static final String KO_KR = "ko_kr";
    public static final String AUTO = "auto";

    private static final Map<String, String> EN = loadJson("/assets/coreprotect_meltdown/lang/en_us.json");
    private static final Map<String, String> KO = loadJson("/assets/coreprotect_meltdown/lang/ko_kr.json");
    private static volatile String mode = normalizeMode(System.getProperty("coreprotect.meltdown.lang", AUTO));

    private MeltdownI18n() {}

    public static String mode() {
        return mode;
    }

    public static boolean setMode(String newMode) {
        if (newMode == null) return false;
        String m = newMode.trim().toLowerCase(Locale.ROOT);
        switch (m) {
            case "auto" -> { mode = AUTO; return true; }
            case "en", "en_us", "english" -> { mode = EN_US; return true; }
            case "ko", "ko_kr", "kr", "korean" -> { mode = KO_KR; return true; }
            default -> { return false; }
        }
    }

    private static String normalizeMode(String raw) {
        if (raw == null) return AUTO;
        String m = raw.trim().toLowerCase(Locale.ROOT);
        if (m.startsWith("en")) return EN_US;
        if (m.startsWith("ko") || m.equals("kr")) return KO_KR;
        return AUTO;
    }

    public static String resolveLang(PlatformSender sender) {
        String currentMode = mode;
        if (EN_US.equals(currentMode) || KO_KR.equals(currentMode)) {
            return currentMode;
        }
        if (sender != null && sender.isPlayer()) {
            String clientLang = detectPlayerLanguage(sender.name());
            if (clientLang != null && !clientLang.isBlank()) {
                return clientLang.toLowerCase(Locale.ROOT).startsWith("ko") ? KO_KR : EN_US;
            }
        }
        String defaultLang = Locale.getDefault().getLanguage();
        return defaultLang != null && defaultLang.toLowerCase(Locale.ROOT).startsWith("ko") ? KO_KR : EN_US;
    }

    public static String get(PlatformSender sender, String key) {
        return getByLang(resolveLang(sender), key);
    }

    public static String tr(PlatformSender sender, String key, Object... args) {
        return trByLang(resolveLang(sender), key, args);
    }

    public static String getByLang(String lang, String key) {
        Map<String, String> table = KO_KR.equalsIgnoreCase(lang) ? KO : EN;
        String val = table.get(key);
        if (val != null) return val;
        String fallback = EN.get(key);
        return fallback != null ? fallback : key;
    }

    public static String trByLang(String lang, String key, Object... args) {
        String pattern = getByLang(lang, key);
        if (args == null || args.length == 0) return pattern;
        return String.format(Locale.ROOT, pattern, args);
    }

    public static Map<String, String> englishMap() {
        return EN;
    }

    public static Map<String, String> koreanMap() {
        return KO;
    }

    private static String detectPlayerLanguage(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        try {
            CoreProtect cp = CoreProtect.getInstance();
            if (cp == null) return null;
            Object server = CoreProtect.class.getMethod("server").invoke(cp);
            if (server == null) return null;
            Object playerList = server.getClass().getMethod("getPlayerList").invoke(server);
            if (playerList == null) return null;
            Object player = playerList.getClass().getMethod("getPlayerByName", String.class).invoke(playerList, playerName);
            if (player == null) return null;
            Object clientInfo = player.getClass().getMethod("clientInformation").invoke(player);
            if (clientInfo == null) return null;
            Method langMethod = clientInfo.getClass().getMethod("language");
            Object lang = langMethod.invoke(clientInfo);
            return lang instanceof String s ? s : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static Map<String, String> loadJson(String resourcePath) {
        try (InputStream in = MeltdownI18n.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing language resource: " + resourcePath);
            }
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parseFlatJsonObject(raw);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static Map<String, String> parseFlatJsonObject(String json) {
        Map<String, String> map = new LinkedHashMap<>();
        int i = 0;
        int n = json.length();
        while (i < n) {
            char c = json.charAt(i);
            if (c == '"') {
                StringBuilder key = new StringBuilder();
                i = readJsonString(json, i + 1, key);
                while (i < n && json.charAt(i) != ':') i++;
                i++; // skip ':'
                while (i < n && Character.isWhitespace(json.charAt(i))) i++;
                if (i < n && json.charAt(i) == '"') {
                    StringBuilder val = new StringBuilder();
                    i = readJsonString(json, i + 1, val);
                    map.put(key.toString(), val.toString());
                }
            } else {
                i++;
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static int readJsonString(String s, int i, StringBuilder out) {
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i++);
            if (c == '"') {
                return i;
            }
            if (c == '\\' && i < n) {
                char esc = s.charAt(i++);
                switch (esc) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 <= n) {
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                        }
                    }
                    default -> out.append(esc);
                }
            } else {
                out.append(c);
            }
        }
        return i;
    }
}

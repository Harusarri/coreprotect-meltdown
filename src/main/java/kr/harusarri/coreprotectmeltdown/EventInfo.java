package kr.harusarri.coreprotectmeltdown;

/** Never retain event payloads, entities, levels, item stacks, or callbacks in statistics. */
public record EventInfo(String world, Integer x, Integer y, Integer z, String actor,
                        String action, boolean automated, boolean maintenance, long payloadChars,
                        String farmMobType, boolean auditCritical) {
    public EventInfo(String world, Integer x, Integer y, Integer z, String actor, String action,
                     boolean automated, boolean maintenance, long payloadChars) {
        this(world, x, y, z, actor, action, automated, maintenance, payloadChars, null, false);
    }
    public boolean located() { return world != null && x != null && y != null && z != null; }
    public static String safe(String s) {
        if (s == null) return "unknown";
        String v = s.length() > 160 ? s.substring(0, 160) : s;
        return v.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').replace('\u001b', '?').replace('\u00a7', '?');
    }
}

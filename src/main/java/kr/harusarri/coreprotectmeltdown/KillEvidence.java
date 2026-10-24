package kr.harusarri.coreprotectmeltdown;

import java.util.Set;

/** CoreProtect 1.2's space-separated metadata; never decode or retain entity NBT. */
public final class KillEvidence {
    private static final Set<String> FARM_MOBS = Set.of(
        "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:zombified_piglin",
        "minecraft:skeleton", "minecraft:stray", "minecraft:bogged", "minecraft:wither_skeleton",
        "minecraft:creeper", "minecraft:spider", "minecraft:cave_spider", "minecraft:enderman",
        "minecraft:slime", "minecraft:magma_cube", "minecraft:blaze", "minecraft:guardian",
        "minecraft:ghast", "minecraft:witch", "minecraft:pillager", "minecraft:vindicator",
        "minecraft:evoker", "minecraft:ravager", "minecraft:hoglin", "minecraft:zoglin",
        "minecraft:piglin", "minecraft:phantom", "minecraft:silverfish", "minecraft:endermite",
        "minecraft:shulker", "minecraft:iron_golem", "minecraft:snow_golem",
        "minecraft:chicken", "minecraft:cow", "minecraft:mooshroom", "minecraft:pig",
        "minecraft:sheep", "minecraft:rabbit", "minecraft:squid", "minecraft:glow_squid",
        "minecraft:cod", "minecraft:salmon", "minecraft:tropical_fish", "minecraft:pufferfish");

    private static final String[] PROTECTED_KEYS = {"player", "custom_name", "owner", "owner_uuid"};
    private KillEvidence() {}
    public static String farmMob(String action, String data) {
        if (!"kill".equals(action) || data == null) return null;
        String type = token(data, "type");
        if (!FARM_MOBS.contains(type == null ? "" : type)) return null;
        // Keep named entities, owned pets and player deaths even beside an overloaded farm.
        for (String key : PROTECTED_KEYS)
            if (token(data, key) != null) return null;
        return type;
    }
    public static boolean critical(String action, String data) {
        // Unknown/malformed metadata fails open for evidence, including non-player actors.
        return "kill".equals(action) && farmMob(action, data) == null;
    }
    private static String token(String data, String key) {
        String prefix = key + "=";
        int start = data.startsWith(prefix) ? 0 : data.indexOf(" " + prefix);
        if (start < 0) return null;
        if (data.charAt(start) == ' ') start++;
        start += prefix.length();
        int end = data.indexOf(' ', start);
        return data.substring(start, end < 0 ? data.length() : end);
    }
}

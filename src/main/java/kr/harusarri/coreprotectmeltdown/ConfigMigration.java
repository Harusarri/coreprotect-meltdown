package kr.harusarri.coreprotectmeltdown;

import java.io.IOException;
import java.nio.file.*;

/** Preserve custom settings and previous reports on the first startup after the rename. */
final class ConfigMigration {
    static void migrate(Path configRoot) throws IOException {
        Path legacy = configRoot.resolve("coreprotect-guard");
        Path current = configRoot.resolve("coreprotect-meltdown");
        if (Files.isDirectory(legacy) && !Files.exists(current)) Files.move(legacy, current);
    }
}

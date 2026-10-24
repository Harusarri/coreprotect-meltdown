package kr.harusarri.coreprotectmeltdown;

import net.fabricmc.api.ModInitializer;

public final class CoreProtectMeltdown implements ModInitializer {
    @Override public void onInitialize() { GuardService.start(); }
}

package kr.harusarri.coreprotectmeltdown.mixin;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import net.coreprotect.command.CommandRegistration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reuse CoreProtect's command source and permission bridge without Minecraft class dependencies. */
@Mixin(value = CommandRegistration.class, remap = false)
public abstract class CommandRegistrationMixin {
    @Shadow private static LiteralArgumentBuilder<Object> root(String label) { throw new AssertionError(); }
    @Shadow private static int execute(CommandContext<Object> context, String label, List<String> args) { throw new AssertionError(); }

    @Inject(method = "register", at = @At("RETURN"), require = 1)
    private static void guard$alias(CommandDispatcher<Object> dispatcher, CallbackInfo ci) {
        dispatcher.register(root("coreprotectmeltdown"));
        dispatcher.register(root("coreprotectguard")); // Retain the previous administrator shortcut.
    }

    @Inject(method = "execute", at = @At("HEAD"), cancellable = true, require = 1)
    private static void guard$executeAlias(CommandContext<Object> context, String label, List<String> args, CallbackInfoReturnable<Integer> cir) {
        if (!label.equals("coreprotectmeltdown") && !label.equals("coreprotectguard")) return;
        List<String> forwarded = new ArrayList<>(args.size() + 1);
        forwarded.add("debug"); forwarded.addAll(args);
        cir.setReturnValue(execute(context, "co", forwarded));
    }

    @Inject(method = "argumentInput", at = @At("RETURN"), cancellable = true, require = 1)
    private static void guard$suggestAlias(String input, String label, CallbackInfoReturnable<String> cir) {
        if (label.equals("coreprotectmeltdown") || label.equals("coreprotectguard")) cir.setReturnValue("debug " + cir.getReturnValue());
    }
}

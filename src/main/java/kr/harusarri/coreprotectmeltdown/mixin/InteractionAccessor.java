package kr.harusarri.coreprotectmeltdown.mixin;

import net.coreprotect.model.entity.EntityInteraction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = EntityInteraction.class, remap = false)
public interface InteractionAccessor {
    @Accessor("metadata") byte[] guard$metadata();
}

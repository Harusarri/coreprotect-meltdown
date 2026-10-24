package kr.harusarri.coreprotectmeltdown.mixin;
import java.util.Map;
import net.coreprotect.database.DatabaseMapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(value = DatabaseMapper.class, remap = false)
public interface WorldCacheAccessor {
    @Accessor("WORLD_CACHE") static Map<String,Integer> meltdown$worlds() { throw new AssertionError(); }
}

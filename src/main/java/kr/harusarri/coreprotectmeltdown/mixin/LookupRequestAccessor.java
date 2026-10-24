package kr.harusarri.coreprotectmeltdown.mixin;
import net.coreprotect.platform.PlatformLocation;
import net.coreprotect.worldedit.WorldEditSelection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(targets = "net.coreprotect.lookup.LookupRequest", remap = false)
public interface LookupRequestAccessor {
    @Accessor("world") String meltdown$world();
    @Accessor("origin") PlatformLocation meltdown$origin();
    @Accessor("radius") int meltdown$radius();
    @Accessor("radiusX") int meltdown$radiusX();
    @Accessor("radiusY") Integer meltdown$radiusY();
    @Accessor("radiusZ") int meltdown$radiusZ();
    @Accessor("page") int meltdown$page();
    @Accessor("rowsPerPage") int meltdown$rowsPerPage();
    @Accessor("worldEditSelection") WorldEditSelection meltdown$worldEditSelection();
    @Invoker("countOnly") boolean meltdown$countOnly();
    @Invoker("summary") boolean meltdown$summary();
}

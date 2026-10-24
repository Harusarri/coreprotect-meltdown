package kr.harusarri.coreprotectmeltdown.mixin;

import java.util.UUID;
import net.coreprotect.consumer.*;
import net.coreprotect.model.entity.*;
import net.coreprotect.services.CoreProtectRuntime;
import net.coreprotect.util.EntitySpawnTracking;
import kr.harusarri.coreprotectmeltdown.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;

/** Wrap the common listener ingress, before pre-log dispatch and queue publication.
 * No vanilla mixins, mappings, or Minecraft classes; requires a compatible CoreProtect runtime.
 * Rollback transitions, database replay, and existing queued records are deliberately untouched.
 */
@Mixin(value = CoreProtectRuntime.class, remap = false)
public abstract class RuntimeMixin {
    @Shadow private volatile Consumer consumer;
    @WrapMethod(method = "log", require = 1)
    private void guard$log(LogEntry entry, Operation<Void> original) {
        if (entry == null) { original.call(entry); return; }
        if (!GuardService.enter(consumer, GuardService.describe(entry))) return;
        try { original.call(entry); } finally { GuardService.leave(); }
    }
    @WrapMethod(method = "logContainer", require = 1)
    private void guard$container(ContainerLogEntry entry, Operation<Void> original) {
        if (entry == null) { original.call(entry); return; }
        if (!GuardService.enter(consumer, GuardService.describe(entry))) return;
        try { original.call(entry); } finally { GuardService.leave(); }
    }
    @WrapMethod(method = "logEntitySpawn", require = 1)
    private void guard$spawn(String actor, EntitySpawnData data, Operation<Void> original) {
        if (data == null || data.getOperation() != null) { original.call(actor, data); return; }
        if (!GuardService.enter(consumer, GuardService.describe(actor, "entity_spawn", data.getLocation(), false, 128))) {
            // forget only removes bookkeeping; unlike confirmMissing it creates no 5-minute tombstone or server task.
            EntitySpawnTracking.forget(data.getUuid()); return;
        }
        try { original.call(actor, data); } finally { GuardService.leave(); }
    }
    @WrapMethod(method = "updateEntityLocation", require = 1)
    private void guard$update(EntitySpawnData data, Operation<Void> original) {
        if (data == null || data.getOperation() == null) { original.call(data); return; }
        if (!GuardService.enter(consumer, GuardService.describe("#entity-maintenance", "entity_update", data.getLocation(), true, 128))) {
            EntitySpawnTracking.forget(data.getUuid()); return;
        }
        try { original.call(data); } finally { GuardService.leave(); }
    }
    @WrapMethod(method = "logTrackedEntityKill", require = 1)
    private void guard$kill(LogEntry entry, UUID uuid, EntityLocation location, Operation<Void> original) {
        if (entry == null || uuid == null) { original.call(entry, uuid, location); return; }
        if (!GuardService.enter(consumer, GuardService.describe(entry))) { EntitySpawnTracking.forget(uuid); return; }
        try { original.call(entry, uuid, location); } finally { GuardService.leave(); }
    }
    @WrapMethod(method = "logEntityContainer", require = 1)
    private void guard$entityContainer(String actor, EntityContainerTransaction transaction, Operation<Void> original) {
        if (transaction == null) { original.call(actor, transaction); return; }
        if (!GuardService.enter(consumer, GuardService.describe(actor, "entity_container", transaction.getCurrentLocation(), false, GuardService.payload(transaction)))) return;
        try { original.call(actor, transaction); } finally { GuardService.leave(); }
    }
    @WrapMethod(method = "logEntityInteraction", require = 1)
    private boolean guard$interaction(String actor, EntityInteraction interaction, Operation<Boolean> original) {
        if (interaction == null) return original.call(actor, interaction);
        byte[] metadata = ((InteractionAccessor)(Object)interaction).guard$metadata();
        long bytes = metadata == null ? 0 : metadata.length;
        if (!GuardService.enter(consumer, GuardService.describe(actor, "entity_interaction", interaction.getCurrentLocation(), false, bytes))) return false;
        try { return original.call(actor, interaction); } finally { GuardService.leave(); }
    }
}

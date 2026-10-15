package areaminer.server.mixin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import areaminer.server.AreaMinerMod;
/**
 * Fabric's AFTER break event fires before tool wear and drops; the area job must start once
 * destroyBlock has fully returned (Paper starts it one tick later).
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class PlayerGameModeMixin {
    @Shadow @Final protected ServerPlayer player;
    @Inject(method="destroyBlock",at=@At("RETURN"))
    private void areaminer$destroyed(BlockPos pos,CallbackInfoReturnable<Boolean> callback) {
        AreaMinerMod.destroyed(player,pos,callback.getReturnValueZ());
    }
}

package areaminer.server.mixin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import areaminer.server.AreaMinerMod;
/** Scales tool wear after Unbreaking, like PlayerItemDamageEvent on Paper. */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
    @Inject(method="processDurabilityChange",at=@At("RETURN"),cancellable=true)
    private void areaminer$scaleDamage(int amount,ServerLevel level,ServerPlayer player,CallbackInfoReturnable<Integer> callback) {
        int original = callback.getReturnValue(), scaled = AreaMinerMod.scaleDamage((ItemStack)(Object)this,player,original);
        if (scaled != original) callback.setReturnValue(scaled);
    }
}

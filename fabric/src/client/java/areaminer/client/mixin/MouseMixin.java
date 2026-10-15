package areaminer.client.mixin;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import areaminer.client.AreaMinerClient;
@Mixin(MouseHandler.class)
public abstract class MouseMixin {
    @Inject(method="onButton",at=@At("HEAD"),cancellable=true)
    private void areaMinerMouse(long handle,MouseButtonInfo event,int action,CallbackInfo callback) {
        if (AreaMinerClient.mouse(action,event)) callback.cancel();
    }
}

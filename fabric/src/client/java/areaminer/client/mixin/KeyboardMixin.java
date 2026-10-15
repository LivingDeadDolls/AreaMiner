package areaminer.client.mixin;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import areaminer.client.AreaMinerClient;
@Mixin(KeyboardHandler.class)
public abstract class KeyboardMixin {
    @Inject(method="keyPress",at=@At("HEAD"),cancellable=true)
    private void areaMinerKey(long handle,int action,KeyEvent event,CallbackInfo callback) {
        if (AreaMinerClient.keyboard(action,event)) callback.cancel();
    }
}

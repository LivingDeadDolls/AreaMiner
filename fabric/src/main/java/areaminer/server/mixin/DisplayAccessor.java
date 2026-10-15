package areaminer.server.mixin;
import com.mojang.math.Transformation;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("setTransformation") void areaminer$setTransformation(Transformation transformation);
    @Invoker("setGlowColorOverride") void areaminer$setGlowColorOverride(int color);
    @Invoker("setViewRange") void areaminer$setViewRange(float range);
    @Invoker("setShadowRadius") void areaminer$setShadowRadius(float radius);
}

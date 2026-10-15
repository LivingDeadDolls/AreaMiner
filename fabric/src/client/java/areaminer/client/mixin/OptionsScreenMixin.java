package areaminer.client.mixin;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import areaminer.client.AreaMinerScreen;
/**
 * Adds a small icon button to the header row (field of view / online), so the options screen gains no extra
 * row: other mods often append rows below the grid, and the screen runs out of height.
 */
@Mixin(OptionsScreen.class)
public abstract class OptionsScreenMixin {
    @Unique private static final Identifier AREAMINER_ICON = Identifier.fromNamespaceAndPath("areaminer_client", "icon");

    // At this point the header's vertical layout (ordinal 0) and its horizontal row (ordinal 1) exist.
    @Inject(method="init",at=@At(value="NEW",target="net/minecraft/client/gui/layouts/GridLayout",ordinal=0))
    private void areaminer$addButton(CallbackInfo callback,@Local(ordinal=1) LinearLayout row) {
        Screen screen = (Screen)(Object)this;
        row.addChild(SpriteIconButton.builder(Component.translatable("areaminer.screen.open"),
                b -> Minecraft.getInstance().setScreen(new AreaMinerScreen(screen)), true)
            .sprite(AREAMINER_ICON, 16, 16).size(20, 20).withTootip().build());
    }
}

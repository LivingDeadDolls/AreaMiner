package areaminer.server.mixin;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import areaminer.server.AreaMinerMod;
/** Sneak + swap-hands toggle for players without the client mod (PlayerSwapHandItemsEvent on Paper). */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PlayerActionMixin {
    @Shadow public ServerPlayer player;
    // After ensureRunningOnSameThread so this runs on the server thread, not the network thread.
    @Inject(method="handlePlayerAction",cancellable=true,at=@At(value="INVOKE",shift=At.Shift.AFTER,
        target="Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V"))
    private void areaminer$swapHands(ServerboundPlayerActionPacket packet,CallbackInfo callback) {
        if (packet.getAction() == ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND && AreaMinerMod.swapHands(player)) callback.cancel();
    }
}

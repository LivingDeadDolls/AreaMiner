package areaminer.server;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import areaminer.protocol.Protocol;
public record SettingsPayload(String text) implements CustomPacketPayload {
    public static final Type<SettingsPayload> TYPE = new Type<>(Identifier.parse(Protocol.CHANNEL));
    public static final StreamCodec<FriendlyByteBuf,SettingsPayload> CODEC = StreamCodec.of(
        (buffer,payload) -> buffer.writeUtf(payload.text(), Protocol.LIMIT), buffer -> new SettingsPayload(buffer.readUtf(Protocol.LIMIT)));
    @Override public Type<SettingsPayload> type() { return TYPE; }
}

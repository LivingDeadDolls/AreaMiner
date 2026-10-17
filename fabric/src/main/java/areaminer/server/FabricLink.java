package areaminer.server;
import java.util.*;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import areaminer.protocol.Protocol;
/** Server side of areaminer:settings — same messages as the Paper ClientLink. */
final class FabricLink {
    private final AreaMinerMod mod;
    private final Set<UUID> clients = new HashSet<>();
    FabricLink(AreaMinerMod mod) { this.mod = mod; }
    void register() {
        ServerPlayNetworking.registerGlobalReceiver(SettingsPayload.TYPE, (payload, context) -> receive(context.player(), payload.text()));
    }
    boolean connected(ServerPlayer p) { return clients.contains(p.getUUID()); }
    void forget(UUID id) { clients.remove(id); }
    void clear() { clients.clear(); }
    private void send(ServerPlayer p, String status, String message) {
        Map<String,String> data = mod.snapshot(p).fields();
        data.put("version", Protocol.VERSION); data.put("status", status); data.put("message", message);
        // The client mod disables its drops button while the server decides where drops go.
        data.put("dropslock", "" + (mod.forcedDrops() != null));
        ServerPlayNetworking.send(p, new SettingsPayload(Protocol.text(data)));
    }
    void changed(ServerPlayer p) { if (connected(p)) send(p, "updated", ""); }
    private void receive(ServerPlayer p, String text) {
        try {
            Map<String,String> data = Protocol.parse(text);
            if (!Protocol.VERSION.equals(data.get("version"))) throw new IllegalArgumentException();
            if (!mod.usePermission(p)) { send(p, "error", AreaMinerMod.messages(p).get("link.permission")); return; }
            switch (data.getOrDefault("op", "")) {
                case "hello" -> { clients.add(p.getUUID()); send(p, "ready", ""); }
                case "next" -> {
                    if (!connected(p)) throw new IllegalArgumentException();
                    mod.select(p, mod.mode(p).next());
                }
                case "apply" -> {
                    if (!connected(p)) throw new IllegalArgumentException();
                    if (mod.applySnapshot(p, Protocol.State.parse(data))) send(p, "applied", AreaMinerMod.messages(p).get("link.applied"));
                    else send(p, "error", AreaMinerMod.messages(p).get("link.save.failed"));
                }
                default -> throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) { send(p, "error", AreaMinerMod.messages(p).get("link.rejected")); }
    }
}

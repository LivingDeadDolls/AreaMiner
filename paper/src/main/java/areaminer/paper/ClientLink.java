package areaminer.paper;

import areaminer.Mode;
import areaminer.Settings;
import areaminer.Shape;
import java.util.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import areaminer.protocol.Protocol;
final class ClientLink implements PluginMessageListener {
    private final AreaMiner plugin;
    private final Set<UUID> clients = new HashSet<>();
    ClientLink(AreaMiner plugin) {
        this.plugin = plugin;
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, Protocol.CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, Protocol.CHANNEL);
    }
    boolean connected(Player p) { return clients.contains(p.getUniqueId()); }
    void forget(Player p) { clients.remove(p.getUniqueId()); }
    void send(Player p, String status, String message) {
        Map<String,String> data = plugin.snapshot(p).fields();
        data.put("version", Protocol.VERSION); data.put("status", status); data.put("message", message);
        p.sendPluginMessage(plugin, Protocol.CHANNEL, Protocol.encode(Protocol.text(data)));
    }
    void changed(Player p) { if (connected(p)) send(p,"updated",""); }
    @Override public void onPluginMessageReceived(String channel, Player p, byte[] bytes) {
        if (!channel.equals(Protocol.CHANNEL) || bytes.length > Protocol.LIMIT + 3) return;
        try {
            Map<String,String> data = Protocol.parse(Protocol.decode(bytes));
            if (!Protocol.VERSION.equals(data.get("version"))) throw new IllegalArgumentException();
            if (!p.hasPermission("areaminer.use")) { send(p,"error",AreaMiner.messages(p).get("link.permission")); return; }
            switch (data.getOrDefault("op", "")) {
                case "hello" -> { clients.add(p.getUniqueId()); send(p,"ready",""); }
                case "next" -> {
                    if (!connected(p)) throw new IllegalArgumentException();
                    plugin.onCommand(p, plugin.getCommand("areaminer"), "areaminer", new String[]{"next"});
                }
                case "apply" -> {
                    if (!connected(p)) throw new IllegalArgumentException();
                    if (plugin.applySnapshot(p, Protocol.State.parse(data))) send(p,"applied",AreaMiner.messages(p).get("link.applied"));
                    else send(p,"error",AreaMiner.messages(p).get("link.save.failed"));
                }
                default -> throw new IllegalArgumentException();
            }
        } catch (RuntimeException e) { send(p,"error",AreaMiner.messages(p).get("link.rejected")); }
    }
}

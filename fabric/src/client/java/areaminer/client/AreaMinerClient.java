package areaminer.client;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import areaminer.Settings;
import areaminer.server.SettingsPayload;
import areaminer.protocol.Protocol;
public final class AreaMinerClient implements ClientModInitializer {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("areaminer-client.json");
    public static final class Config {
        public Protocol.State state = Protocol.State.defaults();
        public boolean pending;
    }
    public static Config config = new Config();
    public static KeyMapping cycleKey, modifierKey, settingsKey;
    public static boolean supported;
    /** The server decides where drops go, so the settings screen cannot change it. */
    public static boolean dropsLocked;
    private static boolean helloPending, applyInFlight;
    /** Ticks since the hello went out, while no reply has come; -1 when not waiting. */
    private static int helloTicks = -1;
    /** No AreaMiner reply within HELLO_TIMEOUT: the server has neither the Fabric mod nor the Paper plugin. */
    private static boolean unsupported;
    /** The version mismatch was already explained in chat during this connection. */
    private static boolean versionNoticed;
    private static final int HELLO_TIMEOUT = 100;
    /** Status line on the settings screen; translated by the client unless it is a message from the server. */
    public static Component status = Component.translatable("areaminer.status.offline");
    /** Set with failures so the screen does not show them in the same colour as success. */
    public static boolean statusError;
    private static void status(String key, boolean error) { status(Component.translatable(key), error); }
    private static void status(Component text, boolean error) { status = text; statusError = error; }
    public static boolean save() {
        try {
            Files.createDirectories(FILE.getParent());
            Path temp = FILE.resolveSibling(FILE.getFileName()+".tmp");
            Files.writeString(temp,JSON.toJson(config));
            Files.move(temp,FILE,StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) { status("areaminer.status.save_failed", true); return false; }
    }
    @Override public void onInitializeClient() {
        try {
            if (Files.exists(FILE)) {
                JsonObject saved = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
                if (saved.has("state") && saved.get("state").isJsonObject()) {
                    // Record components are the protocol keys in camel case. Files written before a field existed
                    // lack it, so it takes its default instead of discarding the whole file.
                    Map<String,String> fields = new LinkedHashMap<>();
                    saved.getAsJsonObject("state").entrySet().forEach(e -> fields.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().getAsString()));
                    Protocol.State.defaults().fields().forEach(fields::putIfAbsent);
                    Config loaded = new Config();
                    loaded.state = Protocol.State.parse(fields);
                    loaded.pending = saved.has("pending") && saved.get("pending").getAsBoolean();
                    // A gaming speed past the screen's limit can only come from editing this file (unsupported, up to
                    // GAMING_SPEED_LIMIT). Send it on the next join like the screen's own changes; otherwise the
                    // server's settings would replace it.
                    if (loaded.state.gamingSpeed() > Settings.MAX_GAMING_SPEED) loaded.pending = true;
                    config = loaded;
                }
            }
        } catch (Exception e) { status("areaminer.status.load_failed", true); }
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("areaminer_client","controls"));
        cycleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.areaminer_client.cycle",InputConstants.Type.KEYSYM,InputConstants.KEY_F,category));
        modifierKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.areaminer_client.modifier",InputConstants.Type.KEYSYM,InputConstants.KEY_LSHIFT,category));
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.areaminer_client.settings",InputConstants.Type.KEYSYM,InputConstants.UNKNOWN.getValue(),category));
        // Payload types are registered by the common entrypoint (AreaMinerMod) for both sides.
        ClientPlayNetworking.registerGlobalReceiver(SettingsPayload.TYPE,(payload,context) -> receive(payload.text()));
        // Fabric sends minecraft:register right after JOIN. Paper drops replies on channels the client has not
        // registered yet, so the hello waits for the next client tick.
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client) -> {
            supported = false; dropsLocked = false; unsupported = false; versionNoticed = false; helloTicks = 0; status("areaminer.status.waiting", false);
            helloPending = true;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (helloPending && client.getConnection() != null) { helloPending = false; send("hello",Map.of()); }
            if (helloTicks >= 0 && client.getConnection() != null && ++helloTicks > HELLO_TIMEOUT) {
                // Nothing answers on this server, so the cycle key and the settings would do nothing: say so once.
                helloTicks = -1; unsupported = true;
                status("areaminer.status.unsupported", false); refresh();
                chat("areaminer.chat.unsupported");
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client) -> {
            supported = false; dropsLocked = false; unsupported = false; helloTicks = -1; helloPending = false; applyInFlight = false; status("areaminer.status.disconnected", false);
        });
        // The options-screen button is added by OptionsScreenMixin.
    }
    private static void send(String op, Map<String,String> values) {
        if (Minecraft.getInstance().getConnection() == null) return;
        Map<String,String> data = new LinkedHashMap<>(values); data.put("version",Protocol.VERSION); data.put("op",op);
        if (op.equals("apply")) applyInFlight = true;
        ClientPlayNetworking.send(new SettingsPayload(Protocol.text(data)));
    }
    private static void receive(String text) {
        try {
            Map<String,String> data = Protocol.parse(text);
            // Any reply means the server has AreaMiner, even a refusal.
            helloTicks = -1; unsupported = false;
            if (!Protocol.VERSION.equals(data.get("version"))) {
                // The server does not treat this client as connected, so its own sneak + swap toggle still works.
                if (!versionNoticed) { versionNoticed = true; chat("areaminer.chat.version"); }
                status("areaminer.status.version", true); refresh(); return;
            }
            dropsLocked = "true".equals(data.get("dropslock"));
            String response = data.get("status");
            if ("error".equals(response)) {
                if (applyInFlight) {
                    // The server rejected these settings: do not re-send them on later joins, and show what the
                    // server actually kept (error replies carry its current settings).
                    applyInFlight = false; config.pending = false;
                    try { config.state = Protocol.State.parse(data); } catch (RuntimeException ignored) { }
                    save();
                }
                // Error texts come from the server, already in the player's language.
                String message = data.get("message");
                status(message == null || message.isEmpty() ? Component.translatable("areaminer.status.rejected") : Component.literal(message), true); refresh(); return;
            }
            Protocol.State serverState = Protocol.State.parse(data);
            supported = true;
            if ("ready".equals(response) && config.pending) { send("apply",config.state.fields()); return; }
            config.state = serverState;
            if ("applied".equals(response)) { config.pending = false; applyInFlight = false; }
            status("applied".equals(response) ? "areaminer.status.applied" : "areaminer.status.connected", false);
            save(); refresh();
        } catch (Exception e) { status("areaminer.status.unreadable", true); refresh(); }
    }
    /** A client-side chat line in the player's language, marked as AreaMiner's. */
    private static void chat(String key) {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendSystemMessage(Component.translatable("areaminer.chat.prefix").append(" ")
            .append(Component.translatable(key)).withStyle(ChatFormatting.GOLD));
    }
    /**
     * The cycle key on a server without AreaMiner: a reminder above the hotbar. The key is not consumed, so it
     * still does whatever vanilla binds to it (swap hands for the default F).
     */
    private static void unsupportedKey() {
        var player = Minecraft.getInstance().player;
        if (player != null) player.sendOverlayMessage(Component.translatable("areaminer.overlay.unsupported").withStyle(ChatFormatting.GOLD));
    }
    private static void refresh() {
        if (Minecraft.getInstance().screen instanceof AreaMinerScreen screen) screen.received();
    }
    public static void apply(Protocol.State state) {
        config.state = state; config.pending = true;
        if (!save()) { refresh(); return; }
        if (supported) { status("areaminer.status.sending", false); send("apply",state.fields()); }
        else status("areaminer.status.saved_offline", false);
    }
    private static boolean inGame() {
        Minecraft client = Minecraft.getInstance();
        return client.player != null && client.screen == null && client.getOverlay() == null;
    }
    public static boolean keyboard(int action, KeyEvent event) {
        if (!inGame()) return false;
        if (settingsKey.matches(event)) {
            if (action == 1) Minecraft.getInstance().setScreen(new AreaMinerScreen(null));
            return true;
        }
        if (unsupported && cycleKey.matches(event) && KeyModifiers.held(modifierKey, event)) {
            if (action == 1) unsupportedKey();
            return false;
        }
        if (supported && cycleKey.matches(event) && KeyModifiers.held(modifierKey, event)) {
            if (action == 1) send("next",Map.of());
            return true;
        }
        return false;
    }
    public static boolean mouse(int action, MouseButtonInfo event) {
        if (!inGame()) return false;
        var mouse = new MouseButtonEvent(0,0,event);
        if (settingsKey.matchesMouse(mouse)) {
            if (action == 1) Minecraft.getInstance().setScreen(new AreaMinerScreen(null));
            return true;
        }
        if (unsupported && cycleKey.matchesMouse(mouse) && KeyModifiers.held(modifierKey, event)) {
            if (action == 1) unsupportedKey();
            return false;
        }
        if (supported && cycleKey.matchesMouse(mouse) && KeyModifiers.held(modifierKey, event)) {
            if (action == 1) send("next",Map.of());
            return true;
        }
        return false;
    }
}

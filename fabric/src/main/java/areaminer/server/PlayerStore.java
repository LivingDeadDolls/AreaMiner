package areaminer.server;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import areaminer.Settings;
/** config/areaminer/players.json — per-UUID preferences, same keys as the Paper players.yml. */
final class PlayerStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private JsonObject root = new JsonObject();
    // Set when an unreadable file could not be backed up: saving would overwrite everyone's settings.
    private boolean readOnly;
    PlayerStore(Path file) {
        this.file = file;
        try { if (Files.exists(file)) root = JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
        catch (Exception e) {
            root = new JsonObject();
            Path backup = file.resolveSibling(file.getFileName() + ".broken-" + System.currentTimeMillis());
            try { Files.move(file, backup); AreaMinerMod.LOG.warn("Cannot read {}, moved it to {}: {}", file, backup, e.getMessage()); }
            catch (Exception moveFailed) {
                try { Files.copy(file, backup); AreaMinerMod.LOG.warn("Cannot read {}, copied it to {}: {}", file, backup, e.getMessage()); }
                catch (Exception copyFailed) {
                    readOnly = true;
                    AreaMinerMod.LOG.error("Cannot read or back up {}; player settings will not be saved until restart: {}", file, e.getMessage());
                }
            }
        }
    }
    Settings load(UUID id) {
        Settings result = new Settings();
        JsonObject o = root.has(id.toString()) && root.get(id.toString()).isJsonObject() ? root.getAsJsonObject(id.toString()) : new JsonObject();
        int pw = integer(o, "plane-width", 3), ph = integer(o, "plane-height", 3);
        if (Settings.validPlane(pw, ph)) { result.planeWidth = pw; result.planeHeight = ph; }
        int cw = integer(o, "cube-width", 3), ch = integer(o, "cube-height", 3), cd = integer(o, "cube-depth", 3);
        if (Settings.validCube(cw, ch, cd)) { result.cubeWidth = cw; result.cubeHeight = ch; result.cubeDepth = cd; }
        int width = integer(o, "stair-width", 2), height = integer(o, "stair-height", 3), steps = integer(o, "stair-steps", 4);
        if (Settings.validStairs(width, height, steps)) {
            result.stairWidth = width; result.stairHeight = height; result.stairSteps = steps;
        }
        int fw = integer(o, "flatten-width", 5), fd = integer(o, "flatten-depth", 5), fh = integer(o, "flatten-height", 5);
        if (Settings.validFlatten(fw, fd, fh)) { result.flattenWidth = fw; result.flattenDepth = fd; result.flattenHeight = fh; }
        result.toolMask = Math.max(0, Math.min(127, integer(o, "tools", Settings.DEFAULT_TOOLS)));
        result.highlight = bool(o, "highlight", true);
        result.dropsAtFeet = bool(o, "drops-at-feet", true);
        int color = integer(o, "highlight-color", Settings.DEFAULT_COLOR);
        if (Settings.validColor(color)) result.highlightColor = color;
        int speed = integer(o, "gaming-speed", Settings.DEFAULT_GAMING_SPEED);
        if (Settings.validGamingSpeed(speed)) result.gamingSpeed = speed;
        double durability = o.has("durability") ? safe(() -> o.get("durability").getAsDouble(), 1d) : 1;
        if (durability == 0 || durability == .5 || durability == 1) result.durability = durability;
        return result;
    }
    void save(UUID id, Settings s) throws Exception {
        JsonObject o = new JsonObject();
        o.addProperty("plane-width", s.planeWidth); o.addProperty("plane-height", s.planeHeight);
        o.addProperty("cube-width", s.cubeWidth); o.addProperty("cube-height", s.cubeHeight); o.addProperty("cube-depth", s.cubeDepth);
        o.addProperty("stair-width", s.stairWidth); o.addProperty("stair-height", s.stairHeight); o.addProperty("stair-steps", s.stairSteps);
        o.addProperty("flatten-width", s.flattenWidth); o.addProperty("flatten-depth", s.flattenDepth); o.addProperty("flatten-height", s.flattenHeight);
        o.addProperty("tools", s.toolMask);
        o.addProperty("highlight", s.highlight); o.addProperty("drops-at-feet", s.dropsAtFeet); o.addProperty("durability", s.durability);
        o.addProperty("highlight-color", s.highlightColor);
        o.addProperty("gaming-speed", s.gamingSpeed);
        root.add(id.toString(), o);
        if (readOnly) throw new IllegalStateException(file + " could not be read or backed up");
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, JSON.toJson(root));
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
    private static int integer(JsonObject o, String key, int fallback) {
        return o.has(key) ? safe(() -> o.get(key).getAsInt(), fallback) : fallback;
    }
    private static boolean bool(JsonObject o, String key, boolean fallback) {
        return o.has(key) ? safe(() -> o.get(key).getAsBoolean(), fallback) : fallback;
    }
    private static <T> T safe(java.util.function.Supplier<T> value, T fallback) {
        try { return value.get(); } catch (RuntimeException e) { return fallback; }
    }
}

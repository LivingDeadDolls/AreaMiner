package areaminer.protocol;
import java.nio.charset.StandardCharsets;
import java.util.*;
import areaminer.Settings;
public final class Protocol {
    public static final String CHANNEL = "areaminer:settings";
    public static final int LIMIT = 4096;
    /** Bump when the fields change; both sides reject other versions. */
    public static final String VERSION = "3";
    /** Most fields in one message: a server reply carries the settings plus version, status, message and dropslock. */
    public static final int MAX_FIELDS = 32;
    public record State(int planeWidth, int planeHeight, int cubeWidth, int cubeHeight, int cubeDepth,
                        int stairWidth, int stairHeight, int stairSteps,
                        int flattenWidth, int flattenDepth, int flattenHeight,
                        boolean highlight, boolean feet, double durability, int tools, int color, int gamingSpeed) {
        public State {
            if (!Settings.validPlane(planeWidth, planeHeight) || !Settings.validCube(cubeWidth, cubeHeight, cubeDepth)
                || !Settings.validStairs(stairWidth, stairHeight, stairSteps)
                || !Settings.validFlatten(flattenWidth, flattenDepth, flattenHeight)
                || (durability != 0 && durability != .5 && durability != 1)
                || tools < 0 || tools > 127 || !Settings.validColor(color) || !Settings.validGamingSpeed(gamingSpeed)) throw new IllegalArgumentException("Invalid settings");
        }
        public static State defaults() { return new State(3,3,3,3,3,2,3,4,5,5,5,true,true,1,Settings.DEFAULT_TOOLS,Settings.DEFAULT_COLOR,Settings.DEFAULT_GAMING_SPEED); }
        public Map<String,String> fields() {
            Map<String,String> map = new LinkedHashMap<>();
            map.put("planewidth", ""+planeWidth); map.put("planeheight", ""+planeHeight);
            map.put("cubewidth", ""+cubeWidth); map.put("cubeheight", ""+cubeHeight); map.put("cubedepth", ""+cubeDepth);
            map.put("stairwidth", ""+stairWidth); map.put("stairheight", ""+stairHeight); map.put("stairsteps", ""+stairSteps);
            map.put("flattenwidth", ""+flattenWidth); map.put("flattendepth", ""+flattenDepth); map.put("flattenheight", ""+flattenHeight);
            map.put("highlight", ""+highlight); map.put("feet", ""+feet); map.put("durability", ""+durability); map.put("tools", ""+tools); map.put("color", ""+color); map.put("gamingspeed", ""+gamingSpeed);
            return map;
        }
        /** The same settings with drops at the feet or not, e.g. when the server decides that for everyone. */
        public State withFeet(boolean value) {
            return new State(planeWidth, planeHeight, cubeWidth, cubeHeight, cubeDepth, stairWidth, stairHeight, stairSteps,
                flattenWidth, flattenDepth, flattenHeight, highlight, value, durability, tools, color, gamingSpeed);
        }
        public static State parse(Map<String,String> map) {
            return new State(integer(map,"planewidth"), integer(map,"planeheight"),
                integer(map,"cubewidth"), integer(map,"cubeheight"), integer(map,"cubedepth"),
                integer(map,"stairwidth"), integer(map,"stairheight"), integer(map,"stairsteps"),
                integer(map,"flattenwidth"), integer(map,"flattendepth"), integer(map,"flattenheight"),
                bool(map,"highlight"), bool(map,"feet"), Double.parseDouble(map.get("durability")), integer(map,"tools"), integer(map,"color"), integer(map,"gamingspeed"));
        }
        public static State of(Settings s) {
            return new State(s.planeWidth, s.planeHeight, s.cubeWidth, s.cubeHeight, s.cubeDepth,
                s.stairWidth, s.stairHeight, s.stairSteps, s.flattenWidth, s.flattenDepth, s.flattenHeight, s.highlight, s.dropsAtFeet, s.durability, s.toolMask, s.highlightColor, s.gamingSpeed);
        }
        public void applyTo(Settings s) {
            s.planeWidth = planeWidth; s.planeHeight = planeHeight;
            s.cubeWidth = cubeWidth; s.cubeHeight = cubeHeight; s.cubeDepth = cubeDepth;
            s.stairWidth = stairWidth; s.stairHeight = stairHeight; s.stairSteps = stairSteps;
            s.flattenWidth = flattenWidth; s.flattenDepth = flattenDepth; s.flattenHeight = flattenHeight;
            s.highlight = highlight; s.dropsAtFeet = feet; s.durability = durability; s.damageRemainder = 0; s.toolMask = tools; s.highlightColor = color; s.gamingSpeed = gamingSpeed;
        }
    }
    private static int integer(Map<String,String> m, String key) { return Integer.parseInt(m.get(key)); }
    private static boolean bool(Map<String,String> m, String key) {
        String value = m.get(key);
        if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException("Invalid boolean");
        return "true".equals(value);
    }
    public static String text(Map<String,String> fields) {
        StringJoiner result = new StringJoiner("\n");
        fields.forEach((key,value) -> {
            if (!key.matches("[a-z]+") || value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) throw new IllegalArgumentException("Invalid field");
            result.add(key + "=" + value);
        });
        String text = result.toString();
        if (text.getBytes(StandardCharsets.UTF_8).length > LIMIT) throw new IllegalArgumentException("Message too long");
        return text;
    }
    public static Map<String,String> parse(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > LIMIT) throw new IllegalArgumentException("Message too long");
        Map<String,String> fields = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            int split = line.indexOf('=');
            if (split <= 0 || line.indexOf('\r') >= 0 || fields.size() >= MAX_FIELDS) throw new IllegalArgumentException("Invalid message");
            String key = line.substring(0,split);
            if (!key.matches("[a-z]+") || fields.putIfAbsent(key,line.substring(split+1)) != null) throw new IllegalArgumentException("Duplicate field");
        }
        return fields;
    }
    public static byte[] encode(String text) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (body.length > LIMIT) throw new IllegalArgumentException("Message too long");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int value = body.length;
        do { int part = value & 127; value >>>= 7; out.write(value == 0 ? part : part | 128); } while (value != 0);
        out.writeBytes(body); return out.toByteArray();
    }
    public static String decode(byte[] bytes) {
        int size = 0, index = 0;
        for (int shift = 0; shift < 21; shift += 7) {
            if (index >= bytes.length) throw new IllegalArgumentException("Truncated message");
            int part = bytes[index++] & 255; size |= (part & 127) << shift;
            if ((part & 128) == 0) {
                if (size > LIMIT || index + size != bytes.length) throw new IllegalArgumentException("Invalid length");
                return new String(bytes,index,size,StandardCharsets.UTF_8);
            }
        }
        throw new IllegalArgumentException("Invalid VarInt");
    }
}

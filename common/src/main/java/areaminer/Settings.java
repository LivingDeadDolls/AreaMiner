package areaminer;
import java.util.*;
public final class Settings {
    /** Tool bits: pickaxe 1, shovel 2, axe 4, hoe 8, shears 16, empty hand 32, other items 64. Default: real tools only. */
    public static final int DEFAULT_TOOLS = 1 | 2 | 4 | 8 | 16;
    public int toolMask = DEFAULT_TOOLS;
    public int planeWidth = 3, planeHeight = 3;
    public int cubeWidth = 3, cubeHeight = 3, cubeDepth = 3;
    public int stairWidth = 2, stairHeight = 3, stairSteps = 4;
    public int flattenWidth = 5, flattenDepth = 5, flattenHeight = 5;
    public boolean highlight = true, dropsAtFeet = true;
    public static final int DEFAULT_COLOR = 0x00FFFF;
    /** Not a color: the highlight cycles through the rainbow, in a wave across the cubes. */
    public static final int GAMING = -1;
    /** Highlight outline color, 0xRRGGBB, or GAMING. */
    public int highlightColor = DEFAULT_COLOR;
    /**
     * How fast the gaming highlight turns: a turn takes 10 - speed seconds (9 at speed 1, 8 at the default 2, 2 at 8).
     * The settings screen and /areaminer go up to MAX_GAMING_SPEED; up to GAMING_SPEED_LIMIT only by editing a settings
     * file, unsupported (see gamingPeriod).
     */
    public static final int DEFAULT_GAMING_SPEED = 2, MAX_GAMING_SPEED = 8, GAMING_SPEED_LIMIT = 12;
    public int gamingSpeed = DEFAULT_GAMING_SPEED;
    /** Speeds the settings files and the settings sync accept (beyond the screen's and the command's MAX_GAMING_SPEED). */
    public static boolean validGamingSpeed(int speed) { return speed >= 1 && speed <= GAMING_SPEED_LIMIT; }
    /** Speeds the settings screen and /areaminer offer. */
    public static boolean selectableGamingSpeed(int speed) { return speed >= 1 && speed <= MAX_GAMING_SPEED; }
    public double durability = 1, damageRemainder;
    /** Mode name with its current range, e.g. "平面 3×5" / "Plane 3×5". */
    public String label(Mode mode, Messages m) {
        String name = mode.label(m);
        return switch (mode) {
            case OFF -> name;
            case PLANE -> m.get("label.plane", name, planeWidth, planeHeight);
            case CUBE -> m.get("label.cube", name, cubeWidth, cubeHeight, cubeDepth);
            case STAIRS -> m.get("label.stairs", name, stairWidth, stairHeight, stairSteps);
            case FLATTEN -> m.get("label.flatten", name, flattenWidth, flattenDepth, flattenHeight);
        };
    }
    /** Named highlight colors, in the order the settings screen steps through them. /areaminer color also takes #RRGGBB. */
    public static final Map<String,Integer> COLORS;
    static {
        Map<String,Integer> colors = new LinkedHashMap<>();
        colors.put("aqua", DEFAULT_COLOR); colors.put("white", 0xFFFFFF); colors.put("red", 0xFF4040);
        colors.put("orange", 0xFF9900); colors.put("yellow", 0xFFFF33); colors.put("lime", 0x66FF33);
        colors.put("blue", 0x3366FF); colors.put("purple", 0xAA44FF); colors.put("pink", 0xFF66CC);
        COLORS = Collections.unmodifiableMap(colors);
    }
    public static boolean validColor(int rgb) { return rgb == GAMING || rgb >= 0 && rgb <= 0xFFFFFF; }
    /** The color's name in COLORS ("gaming" for GAMING), or null for any other color. */
    public static String colorName(int rgb) {
        if (rgb == GAMING) return "gaming";
        for (var entry : COLORS.entrySet()) if (entry.getValue() == rgb) return entry.getKey();
        return null;
    }
    /** A name from COLORS, gaming (or rainbow), or #RRGGBB / RRGGBB. */
    public static int parseColor(String text) {
        String value = text.toLowerCase(Locale.ROOT);
        if (value.equals("gaming") || value.equals("rainbow")) return GAMING;
        Integer named = COLORS.get(value);
        if (named != null) return named;
        if (value.startsWith("#")) value = value.substring(1);
        if (!value.matches("[0-9a-f]{6}")) throw new IllegalArgumentException("Invalid color");
        return Integer.parseInt(value, 16);
    }
    public static String hex(int rgb) { return String.format(Locale.ROOT, "#%06X", rgb); }
    /** Degrees of hue between neighbouring blocks of the gaming highlight. */
    public static final int GAMING_WAVE = 24;
    /**
     * Ticks one turn of the gaming highlight takes: 10 - speed seconds up to speed 9 (one second), then 1 / (speed - 8)
     * seconds in whole ticks (speeds 10-12 come only from settings files: 0.5, 0.3 and 0.25 seconds).
     */
    public static int gamingPeriod(int speed) { return speed <= 9 ? (10 - speed) * 20 : 20 / (speed - 8); }
    /** A turn's length for people, in seconds: "8", "0.5", "0.3". */
    public static String gamingSeconds(int speed) {
        double seconds = gamingPeriod(speed) / 20.0;
        return seconds == Math.rint(seconds) ? Integer.toString((int) seconds)
            : new java.math.BigDecimal(seconds).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
    /**
     * The gaming highlight's color for the block at x, y, z at the given tick and speed: a rainbow wave running
     * across the area.
     */
    public static int gaming(long tick, int speed, int x, int y, int z) { return gaming(tick, speed, x, y, z, 1); }
    /** As gaming, with the hue rounded down to a multiple of quantum degrees (see GamingPlan). */
    public static int gaming(long tick, int speed, int x, int y, int z, int quantum) {
        int degrees = (int) Math.floorMod(tick * 360 / gamingPeriod(speed) - (long) (x + y + z) * GAMING_WAVE, 360L);
        return hue(degrees - degrees % quantum);
    }
    /** Color updates per second the gaming highlight may send one player, over all its cubes (about 11 bytes each). */
    public static final int GAMING_BUDGET = 2000;
    /** Ticks between gaming updates while the budget allows, and the coarsest hue steps before updating less often. */
    public static final int GAMING_INTERVAL = 2, GAMING_MAX_QUANTUM = 60;
    /**
     * How the gaming highlight keeps within GAMING_BUDGET: a cube is only sent again when its hue has moved by a whole
     * quantum (the color is rounded to it), and when even GAMING_MAX_QUANTUM is too fine, updates come every interval
     * ticks instead of every GAMING_INTERVAL. Small areas keep the full smoothness (quantum 1 or close to it).
     */
    public record GamingPlan(int quantum, int interval) {}
    public static GamingPlan gamingPlan(int cubes, int speed) {
        long degreesPerSecond = ceil(360L * 20, gamingPeriod(speed));
        // Each cube changes color degreesPerSecond / quantum times a second.
        int quantum = (int) Math.max(1, Math.min(GAMING_MAX_QUANTUM, ceil(cubes * degreesPerSecond, GAMING_BUDGET)));
        int interval = GAMING_INTERVAL;
        if (cubes * degreesPerSecond / quantum > GAMING_BUDGET) {
            // Every cube may change at each update: at most 20 / interval updates a second each.
            interval = (int) Math.max(GAMING_INTERVAL, ceil(cubes * 20L, GAMING_BUDGET));
        }
        return new GamingPlan(quantum, interval);
    }
    private static long ceil(long a, long b) { return (a + b - 1) / b; }
    /** Fully saturated, full brightness color of hue 0-359 (0 red, 120 green, 240 blue). */
    public static int hue(int degrees) {
        int sector = degrees / 60, rise = (degrees % 60) * 255 / 60, fall = 255 - rise;
        int r, g, b;
        switch (sector) {
            case 0 -> { r = 255; g = rise; b = 0; }
            case 1 -> { r = fall; g = 255; b = 0; }
            case 2 -> { r = 0; g = 255; b = rise; }
            case 3 -> { r = 0; g = fall; b = 255; }
            case 4 -> { r = rise; g = 0; b = 255; }
            default -> { r = 255; g = 0; b = fall; }
        }
        return r << 16 | g << 8 | b;
    }
    /** "Aqua" / "水色" for a named color, otherwise #RRGGBB. */
    public static String colorLabel(int rgb, Messages m) {
        String name = colorName(rgb);
        return name == null ? hex(rgb) : m.get("color." + name);
    }
    /** This player's highlight color for chat: as colorLabel, with the speed for the gaming highlight. */
    public String colorLabel(Messages m) {
        String label = colorLabel(highlightColor, m);
        return highlightColor == GAMING ? m.get("color.gaming.speed", label, gamingSpeed, gamingSeconds(gamingSpeed)) : label;
    }
    /** The server's drops setting: "player" lets each player choose (null); "feet" and "block" decide for everyone. */
    public static Boolean dropsPolicy(String value) {
        return switch (value == null ? "player" : value.toLowerCase(Locale.ROOT)) {
            case "player" -> null;
            case "feet" -> true;
            case "block" -> false;
            default -> throw new IllegalArgumentException("Invalid drops setting: " + value);
        };
    }
    public String durabilityLabel(Messages m) { return m.get(durability == 1 ? "durability.normal" : durability == .5 ? "durability.half" : "durability.none"); }
    /** Each side of a plane or cube is 1-10 blocks; a single block (1×1) is not an area. */
    public static final int MAX_SIDE = 10;
    public static boolean validSide(int n) { return n >= 1 && n <= MAX_SIDE; }
    public static boolean validPlane(int width, int height) {
        return validSide(width) && validSide(height) && width * height > 1;
    }
    public static boolean validCube(int width, int height, int depth) {
        return validSide(width) && validSide(height) && validSide(depth) && width * height * depth > 1;
    }
    /** Flatten uses the cube limits: each side 1-10 (at most 1000 blocks), not a single block. */
    public static boolean validFlatten(int width, int depth, int height) { return validCube(width, depth, height); }
    public static boolean validStairs(int width, int height, int steps) {
        return width >= 1 && width <= 10 && height >= 2 && height <= 10 && steps >= 1 && steps <= 10;
    }
    public int scaledDamage(int original) {
        double total = original * durability + damageRemainder;
        int damage = (int) Math.floor(total);
        damageRemainder = total - damage;
        return damage;
    }
}

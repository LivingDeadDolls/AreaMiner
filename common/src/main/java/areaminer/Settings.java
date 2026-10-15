package areaminer;
public final class Settings {
    /** Tool bits: pickaxe 1, shovel 2, axe 4, hoe 8, shears 16, empty hand 32, other items 64. Default: real tools only. */
    public static final int DEFAULT_TOOLS = 1 | 2 | 4 | 8 | 16;
    public int toolMask = DEFAULT_TOOLS;
    public int planeWidth = 3, planeHeight = 3;
    public int cubeWidth = 3, cubeHeight = 3, cubeDepth = 3;
    public int stairWidth = 2, stairHeight = 3, stairSteps = 4;
    public int flattenWidth = 5, flattenDepth = 5, flattenHeight = 5;
    public boolean highlight = true, dropsAtFeet = true;
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

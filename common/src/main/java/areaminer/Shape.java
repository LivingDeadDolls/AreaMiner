package areaminer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Coordinates relative to the initially broken block. Normal points toward the player. */
public final class Shape {
    public record Offset(int x, int y, int z) {}
    private Shape() {}
    /** Four ascending or descending steps; origin is the bottom block of the first opening. */
    public static List<Offset> stairs(int dx, int dz, boolean ascending) {
        return stairs(dx, dz, ascending, 2, 3, 4);
    }
    public static List<Offset> stairs(int dx, int dz, boolean ascending, int width, int height, int steps) {
        if (!Settings.validStairs(width, height, steps)) throw new IllegalArgumentException("Invalid staircase dimensions");
        if (Math.abs(dx) + Math.abs(dz) != 1) {
            throw new IllegalArgumentException("Direction must be horizontal axis unit vector");
        }
        List<Offset> result = new ArrayList<>();
        for (int step = 0; step < steps; step++) {
            for (int side = 0; side < width; side++) {
                for (int h = 0; h < height; h++) {
                    int x = dx * step - dz * side;
                    int y = (ascending ? step : -step) + h;
                    int z = dz * step + dx * side;
                    if (x != 0 || y != 0 || z != 0) result.add(new Offset(x, y, z));
                }
            }
        }
        return result;
    }
    /**
     * Area dug from the broken block: width × height across the face, depth blocks into it (1 for a plane).
     * <ul>
     * <li>Walls (horizontal normal): width is left-right, height is vertical and starts at the player's feet
     *     (feet = feet block Y minus broken block Y), shifted only as far as needed to contain the broken block.</li>
     * <li>Floors and ceilings: depth goes down/up; width is left-right and height is front-back as seen by the
     *     player, whose horizontal facing runs along X when facingX is true. Both are centred.</li>
     * </ul>
     * Even sides are centred with the extra block on the positive axis.
     */
    public static List<Offset> box(int width, int height, int depth, int nx, int ny, int nz, int feet, boolean facingX) {
        if (Math.abs(nx) + Math.abs(ny) + Math.abs(nz) != 1) {
            throw new IllegalArgumentException("Normal must be one axis unit vector");
        }
        if (!Settings.validCube(width, height, depth)) throw new IllegalArgumentException("Invalid area dimensions");
        int widthLow = -(width - 1) / 2;
        int heightLow = ny != 0 ? -(height - 1) / 2 : Math.max(-(height - 1), Math.min(0, feet));
        List<Offset> result = new ArrayList<>();
        for (int d = 0; d < depth; d++) {
            for (int u = widthLow; u < widthLow + width; u++) {
                for (int v = heightLow; v < heightLow + height; v++) {
                    int x, y, z;
                    if (ny != 0) {
                        y = -ny * d;
                        // Front-back (height) follows the facing axis; left-right (width) is the other one.
                        if (facingX) { x = v; z = u; } else { x = u; z = v; }
                    } else if (nx != 0) {
                        x = -nx * d; y = v; z = u;
                    } else {
                        z = -nz * d; y = v; x = u;
                    }
                    if (x != 0 || y != 0 || z != 0) result.add(new Offset(x, y, z));
                }
            }
        }
        result.sort(Comparator.comparingInt(o -> o.x()*o.x() + o.y()*o.y() + o.z()*o.z()));
        return result;
    }
    /**
     * Flatten: width × depth around the broken block, height blocks up from the floor, air included. The floor is
     * the player's feet (feet = feet block Y minus broken block Y), or the broken block when it is lower.
     * Width is left-right and depth front-back as seen by the player, whose horizontal facing runs along X when
     * facingX is true; both are centred like box(). The top layer comes first so sand and gravel above dug
     * cells fall into cells that are mined later instead of being left behind.
     */
    public static List<Offset> flatten(int width, int depth, int height, int feet, boolean facingX) {
        if (!Settings.validFlatten(width, depth, height)) throw new IllegalArgumentException("Invalid flatten dimensions");
        int floor = Math.min(0, feet);
        int widthLow = -(width - 1) / 2, depthLow = -(depth - 1) / 2;
        List<Offset> result = new ArrayList<>();
        for (int y = floor + height - 1; y >= floor; y--) {
            List<Offset> layer = new ArrayList<>();
            for (int u = widthLow; u < widthLow + width; u++) {
                for (int v = depthLow; v < depthLow + depth; v++) {
                    int x = facingX ? v : u, z = facingX ? u : v;
                    if (x != 0 || y != 0 || z != 0) layer.add(new Offset(x, y, z));
                }
            }
            layer.sort(Comparator.comparingInt(o -> o.x()*o.x() + o.z()*o.z()));
            result.addAll(layer);
        }
        return result;
    }
}

package areaminer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Ore vein search: every block of the same ore connected to the broken one, instead of a fixed area. */
public final class Vein {
    /** Blocks searched in each direction from the broken block; keeps a vein job inside the 32-block stop distance. */
    public static final int RADIUS = 16;

    private Vein() {}

    /** Tells whether the block at an offset from the broken block belongs to the same vein. */
    public interface Probe {
        boolean sameVein(int dx, int dy, int dz);
    }

    /**
     * Breadth-first flood fill from the broken block (not included), nearest blocks first, up to max blocks.
     * Neighbours include edges and corners (26 directions): ore veins often touch only diagonally.
     */
    public static List<Shape.Offset> find(int max, Probe probe) {
        List<Shape.Offset> result = new ArrayList<>();
        Set<Shape.Offset> seen = new HashSet<>();
        ArrayDeque<Shape.Offset> queue = new ArrayDeque<>();
        Shape.Offset origin = new Shape.Offset(0, 0, 0);
        seen.add(origin); queue.add(origin);
        while (!queue.isEmpty() && result.size() < max) {
            Shape.Offset current = queue.poll();
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                Shape.Offset next = new Shape.Offset(current.x() + dx, current.y() + dy, current.z() + dz);
                if (Math.abs(next.x()) > RADIUS || Math.abs(next.y()) > RADIUS || Math.abs(next.z()) > RADIUS) continue;
                if (!seen.add(next) || !probe.sameVein(next.x(), next.y(), next.z())) continue;
                if (result.size() >= max) return result;
                result.add(next); queue.add(next);
            }
        }
        return result;
    }

    /**
     * Ore kind from a block id, so the stone and deepslate variants of one ore form a single vein:
     * "minecraft:deepslate_iron_ore" and "minecraft:iron_ore" are both "minecraft:iron".
     */
    public static String family(String id) {
        String value = id.toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : value.substring(0, colon), path = colon < 0 ? value : value.substring(colon + 1);
        if (path.startsWith("deepslate_")) path = path.substring("deepslate_".length());
        if (path.endsWith("_ore")) path = path.substring(0, path.length() - "_ore".length());
        return namespace + ":" + path;
    }
}

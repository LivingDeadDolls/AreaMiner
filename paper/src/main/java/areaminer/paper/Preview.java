package areaminer.paper;

import areaminer.Mode;
import areaminer.Settings;
import areaminer.Shape;

import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Owner-only, nonpersistent visual entities. Mining and preview share geometry. */
final class Preview {
    private static final String TAG = "areaminer_preview_v1";
    private record Position(UUID world, int x, int y, int z) {}
    private final AreaMiner plugin;
    private final Map<UUID, Map<Position, BlockDisplay>> displays = new HashMap<>();

    Preview(AreaMiner plugin) { this.plugin = plugin; }
    void clearStale() {
        for (World world : plugin.getServer().getWorlds()) {
            for (BlockDisplay display : world.getEntitiesByClass(BlockDisplay.class)) {
                if (display.getScoreboardTags().contains(TAG)) display.remove();
            }
        }
    }
    void clear(UUID player) {
        var old = displays.remove(player);
        if (old != null) old.values().forEach(BlockDisplay::remove);
    }
    void clear() {
        for (UUID player : new ArrayList<>(displays.keySet())) clear(player);
    }
    /** The cube sits inside its block; once the block is gone it would show as a white cube until the next update. */
    void removeAt(UUID player, Block block) {
        var current = displays.get(player);
        BlockDisplay display = current == null ? null
            : current.remove(new Position(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
        if (display != null) display.remove();
    }
    void update() {
        for (UUID id : new ArrayList<>(displays.keySet())) {
            if (plugin.getServer().getPlayer(id) == null) clear(id);
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!plugin.previewAllowed(player)) { clear(player.getUniqueId()); continue; }
            RayTraceResult hit = player.rayTraceBlocks(player.getGameMode() == GameMode.CREATIVE ? 5 : 4.5,
                FluidCollisionMode.NEVER);
            if (hit == null || hit.getHitBlock() == null || !plugin.minable(player, hit.getHitBlock())) {
                clear(player.getUniqueId()); continue;
            }
            Block origin = hit.getHitBlock();
            World world = origin.getWorld();
            Set<Position> wanted = new LinkedHashSet<>();
            add(wanted, world, origin.getX(), origin.getY(), origin.getZ());
            for (Shape.Offset offset : plugin.plan(player, origin).keySet()) {
                add(wanted, world, origin.getX()+offset.x(), origin.getY()+offset.y(), origin.getZ()+offset.z());
            }
            Map<Position, BlockDisplay> current = displays.get(player.getUniqueId());
            if (current == null) {
                current = new HashMap<>();
                displays.put(player.getUniqueId(), current);
            }
            var iterator = current.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (!wanted.contains(entry.getKey()) || !entry.getValue().isValid()) {
                    entry.getValue().remove(); iterator.remove();
                }
            }
            for (Position position : wanted) {
                if (current.containsKey(position)) continue;
                BlockDisplay display = world.spawn(new Location(world, position.x(), position.y(), position.z()),
                    BlockDisplay.class, entity -> {
                        entity.setVisibleByDefault(false);
                        entity.setPersistent(false);
                        entity.setGravity(false);
                        entity.setInvulnerable(true);
                        entity.addScoreboardTag(TAG);
                        // Slightly inset cubes leave a visible gap between adjacent outlines.
                        entity.setBlock(Material.WHITE_CONCRETE.createBlockData());
                        entity.setTransformation(new Transformation(new Vector3f(.02f, .02f, .02f),
                            new Quaternionf(), new Vector3f(.96f, .96f, .96f), new Quaternionf()));
                        entity.setGlowing(true);
                        entity.setGlowColorOverride(Color.AQUA);
                        entity.setViewRange(1f);
                        entity.setShadowRadius(0);
                    });
                current.put(position, display);
                player.showEntity(plugin, display);
            }
        }
    }
    private void add(Set<Position> wanted, World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return;
        // Positions come from plugin.plan, which already checked the materials.
        Block block = world.getBlockAt(x, y, z);
        if (world.getWorldBorder().isInside(block.getLocation())) {
            wanted.add(new Position(world.getUID(), x, y, z));
        }
    }
}

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
    /** Tick of each player's last gaming update. */
    private final Map<UUID, Integer> lastGaming = new HashMap<>();

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
        lastGaming.remove(player);
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
            int color = plugin.highlightColor(player);
            int speed = plugin.gamingSpeed(player), quantum = Settings.gamingPlan(wanted.size(), speed).quantum();
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
                        // Inset just enough that the cube does not overlap the block's faces (flush, it covers them
                        // black). The glow outlines the whole area as one; a wider gap only left notches at each seam.
                        entity.setBlock(Material.WHITE_CONCRETE.createBlockData());
                        entity.setTransformation(new Transformation(new Vector3f(.002f, .002f, .002f),
                            new Quaternionf(), new Vector3f(.996f, .996f, .996f), new Quaternionf()));
                        entity.setGlowing(true);
                        entity.setGlowColorOverride(Color.fromRGB(color == Settings.GAMING
                            ? Settings.gaming(plugin.getServer().getCurrentTick(), speed, position.x(), position.y(), position.z(), quantum) : color));
                        entity.setViewRange(1f);
                        entity.setShadowRadius(0);
                    });
                current.put(position, display);
                player.showEntity(plugin, display);
            }
        }
    }
    /**
     * Moves the gaming highlight along the rainbow: each cube gets its color for this tick (Paper sends only the cubes
     * whose color changed). Large areas keep to Settings.GAMING_BUDGET: coarser hue steps, then fewer updates.
     */
    void animate() {
        int tick = plugin.getServer().getCurrentTick();
        for (var entry : displays.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || plugin.highlightColor(player) != Settings.GAMING) continue;
            int speed = plugin.gamingSpeed(player);
            Settings.GamingPlan plan = Settings.gamingPlan(Math.max(1, entry.getValue().size()), speed);
            Integer last = lastGaming.get(entry.getKey());
            if (last != null && tick - last < plan.interval()) continue;
            lastGaming.put(entry.getKey(), tick);
            entry.getValue().forEach((pos, display) -> {
                if (display.isValid()) display.setGlowColorOverride(Color.fromRGB(Settings.gaming(tick, speed, pos.x(), pos.y(), pos.z(), plan.quantum())));
            });
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

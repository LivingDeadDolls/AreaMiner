package areaminer.server;
import java.util.*;
import com.mojang.math.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import areaminer.Settings;
import areaminer.Shape;
import areaminer.server.mixin.BlockDisplayAccessor;
import areaminer.server.mixin.DisplayAccessor;
/**
 * Owner-only highlight. Vanilla has no per-player entity visibility, so the block displays are never
 * added to the world: they exist only as packets sent to their owner. Mining and preview share geometry.
 */
final class FabricPreview {
    private final AreaMinerMod mod;
    private final Map<UUID, Map<BlockPos, Display.BlockDisplay>> displays = new HashMap<>();
    /** Tick of each player's last gaming update. */
    private final Map<UUID, Integer> lastGaming = new HashMap<>();
    FabricPreview(AreaMinerMod mod) { this.mod = mod; }

    /** Forget without sending packets (player left or changed dimension; the client already dropped them). */
    void forget(UUID player) { displays.remove(player); lastGaming.remove(player); }
    /** The cube sits inside its block; once the block is gone it would show as a white cube until the next update. */
    void removeAt(ServerPlayer player, BlockPos pos) {
        var current = displays.get(player.getUUID());
        Display.BlockDisplay cube = current == null ? null : current.remove(pos);
        if (cube != null && !player.hasDisconnected()) player.connection.send(new ClientboundRemoveEntitiesPacket(cube.getId()));
    }
    void clear(ServerPlayer player) {
        var old = displays.remove(player.getUUID());
        if (old != null && !old.isEmpty() && !player.hasDisconnected()) {
            player.connection.send(new ClientboundRemoveEntitiesPacket(old.values().stream().mapToInt(Display.BlockDisplay::getId).toArray()));
        }
    }
    /** Removes every player's highlight on their client (the cubes exist only as packets). */
    void clear(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) clear(player);
        displays.clear();
    }
    /** Server shutdown: clients disconnect and drop the entities themselves. */
    void forgetAll() { displays.clear(); lastGaming.clear(); }
    void update(MinecraftServer server) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            if (!mod.previewAllowed(player)) { clear(player); continue; }
            BlockHitResult hit = AreaMinerMod.rayTrace(player, player.isCreative() ? 5 : 4.5);
            ServerLevel world = player.level();
            if (hit.getType() != HitResult.Type.BLOCK || !mod.minable(player, world.getBlockState(hit.getBlockPos()))) {
                clear(player); continue;
            }
            BlockPos origin = hit.getBlockPos();
            Set<BlockPos> wanted = new LinkedHashSet<>();
            add(wanted, world, origin);
            for (Shape.Offset offset : mod.plan(player, origin).keySet()) add(wanted, world, origin.offset(offset.x(), offset.y(), offset.z()));
            Map<BlockPos, Display.BlockDisplay> current = displays.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
            List<Integer> removed = new ArrayList<>();
            current.entrySet().removeIf(entry -> {
                if (wanted.contains(entry.getKey())) return false;
                removed.add(entry.getValue().getId()); return true;
            });
            if (!removed.isEmpty()) player.connection.send(new ClientboundRemoveEntitiesPacket(removed.stream().mapToInt(Integer::intValue).toArray()));
            for (BlockPos position : wanted) {
                if (!current.containsKey(position)) current.put(position, spawn(player, world, position));
            }
        }
        displays.keySet().removeIf(id -> !online.contains(id));
    }
    private Display.BlockDisplay spawn(ServerPlayer player, ServerLevel world, BlockPos position) {
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityType.BLOCK_DISPLAY, world);
        entity.setPos(position.getX(), position.getY(), position.getZ());
        ((BlockDisplayAccessor) entity).areaminer$setBlockState(Blocks.WHITE_CONCRETE.defaultBlockState());
        DisplayAccessor display = (DisplayAccessor) entity;
        // Inset just enough that the cube does not overlap the block's faces (flush, it covers them black). The glow
        // outlines the whole area as one; a wider gap only left notches in it at each seam.
        display.areaminer$setTransformation(new Transformation(new Vector3f(.002f, .002f, .002f),
            new Quaternionf(), new Vector3f(.996f, .996f, .996f), new Quaternionf()));
        Settings settings = mod.settings(player);
        int color = settings.highlightColor;
        display.areaminer$setGlowColorOverride(color == Settings.GAMING ? Settings.gaming(world.getServer().getTickCount(), settings.gamingSpeed,
            position.getX(), position.getY(), position.getZ(), gamingPlan(player).quantum()) : color);
        display.areaminer$setViewRange(1f);
        display.areaminer$setShadowRadius(0f);
        entity.setGlowingTag(true);
        player.connection.send(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(),
            position.getX(), position.getY(), position.getZ(), 0f, 0f, EntityType.BLOCK_DISPLAY, 0, Vec3.ZERO, 0d));
        var values = entity.getEntityData().getNonDefaultValues();
        if (values != null) player.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), values));
        // Sent: later changes (the gaming colors) go out as just what changed.
        entity.getEntityData().packDirty();
        return entity;
    }
    /**
     * Moves the gaming highlight along the rainbow: each cube gets its color for this tick, all of a player's changes
     * in one bundle. Cheap when nobody uses it; the cubes themselves are only replaced by update. Large areas keep to
     * Settings.GAMING_BUDGET: coarser hue steps (only cubes whose color changed are sent), then fewer updates.
     */
    private Settings.GamingPlan gamingPlan(ServerPlayer player) {
        var cubes = displays.get(player.getUUID());
        return Settings.gamingPlan(cubes == null ? 1 : Math.max(1, cubes.size()), mod.settings(player).gamingSpeed);
    }
    void animate(MinecraftServer server) {
        int tick = server.getTickCount();
        for (var entry : displays.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.hasDisconnected() || entry.getValue().isEmpty()
                || mod.settings(player).highlightColor != Settings.GAMING) continue;
            Settings.GamingPlan plan = gamingPlan(player);
            Integer last = lastGaming.get(entry.getKey());
            if (last != null && tick - last < plan.interval()) continue;
            lastGaming.put(entry.getKey(), tick);
            int speed = mod.settings(player).gamingSpeed;
            List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
            entry.getValue().forEach((pos, cube) -> {
                ((DisplayAccessor) cube).areaminer$setGlowColorOverride(Settings.gaming(tick, speed, pos.getX(), pos.getY(), pos.getZ(), plan.quantum()));
                var changed = cube.getEntityData().packDirty();
                if (changed != null) packets.add(new ClientboundSetEntityDataPacket(cube.getId(), changed));
            });
            if (!packets.isEmpty()) player.connection.send(new ClientboundBundlePacket(packets));
        }
    }
    private void add(Set<BlockPos> wanted, ServerLevel world, BlockPos pos) {
        // Positions come from mod.plan, which already checked the blocks.
        if (AreaMinerMod.inside(world, pos)) wanted.add(pos.immutable());
    }
}

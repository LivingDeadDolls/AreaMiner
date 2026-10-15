package areaminer.server;
import java.util.*;
import com.mojang.math.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import areaminer.Shape;
import areaminer.server.mixin.BlockDisplayAccessor;
import areaminer.server.mixin.DisplayAccessor;
/**
 * Owner-only highlight. Vanilla has no per-player entity visibility, so the block displays are never
 * added to the world: they exist only as packets sent to their owner. Mining and preview share geometry.
 */
final class FabricPreview {
    private final AreaMinerMod mod;
    private final Map<UUID, Map<BlockPos, Integer>> displays = new HashMap<>();
    FabricPreview(AreaMinerMod mod) { this.mod = mod; }

    /** Forget without sending packets (player left or changed dimension; the client already dropped them). */
    void forget(UUID player) { displays.remove(player); }
    /** The cube sits inside its block; once the block is gone it would show as a white cube until the next update. */
    void removeAt(ServerPlayer player, BlockPos pos) {
        var current = displays.get(player.getUUID());
        Integer id = current == null ? null : current.remove(pos);
        if (id != null && !player.hasDisconnected()) player.connection.send(new ClientboundRemoveEntitiesPacket(id));
    }
    void clear(ServerPlayer player) {
        var old = displays.remove(player.getUUID());
        if (old != null && !old.isEmpty() && !player.hasDisconnected()) {
            player.connection.send(new ClientboundRemoveEntitiesPacket(old.values().stream().mapToInt(Integer::intValue).toArray()));
        }
    }
    /** Removes every player's highlight on their client (the cubes exist only as packets). */
    void clear(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) clear(player);
        displays.clear();
    }
    /** Server shutdown: clients disconnect and drop the entities themselves. */
    void forgetAll() { displays.clear(); }
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
            Map<BlockPos, Integer> current = displays.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
            List<Integer> removed = new ArrayList<>();
            current.entrySet().removeIf(entry -> {
                if (wanted.contains(entry.getKey())) return false;
                removed.add(entry.getValue()); return true;
            });
            if (!removed.isEmpty()) player.connection.send(new ClientboundRemoveEntitiesPacket(removed.stream().mapToInt(Integer::intValue).toArray()));
            for (BlockPos position : wanted) {
                if (!current.containsKey(position)) current.put(position, spawn(player, world, position));
            }
        }
        displays.keySet().removeIf(id -> !online.contains(id));
    }
    private int spawn(ServerPlayer player, ServerLevel world, BlockPos position) {
        Display.BlockDisplay entity = new Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, world);
        entity.setPos(position.getX(), position.getY(), position.getZ());
        ((BlockDisplayAccessor) entity).areaminer$setBlockState(Blocks.CONCRETE.white().defaultBlockState());
        DisplayAccessor display = (DisplayAccessor) entity;
        // Slightly inset cubes leave a visible gap between adjacent outlines.
        display.areaminer$setTransformation(new Transformation(new Vector3f(.02f, .02f, .02f),
            new Quaternionf(), new Vector3f(.96f, .96f, .96f), new Quaternionf()));
        display.areaminer$setGlowColorOverride(0x00FFFF);
        display.areaminer$setViewRange(1f);
        display.areaminer$setShadowRadius(0f);
        entity.setGlowingTag(true);
        player.connection.send(new ClientboundAddEntityPacket(entity.getId(), entity.getUUID(),
            position.getX(), position.getY(), position.getZ(), 0f, 0f, EntityTypes.BLOCK_DISPLAY, 0, Vec3.ZERO, 0d));
        var values = entity.getEntityData().getNonDefaultValues();
        if (values != null) player.connection.send(new ClientboundSetEntityDataPacket(entity.getId(), values));
        return entity.getId();
    }
    private void add(Set<BlockPos> wanted, ServerLevel world, BlockPos pos) {
        // Positions come from mod.plan, which already checked the blocks.
        if (AreaMinerMod.inside(world, pos)) wanted.add(pos.immutable());
    }
}

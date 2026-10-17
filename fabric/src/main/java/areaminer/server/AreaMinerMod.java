package areaminer.server;

import java.util.*;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import areaminer.CommandText;
import areaminer.Messages;
import areaminer.Mode;
import areaminer.Settings;
import areaminer.Shape;
import areaminer.Vein;
import areaminer.protocol.Protocol;

/** Fabric port of the Paper plugin. Runs on dedicated servers and on the singleplayer integrated server. */
public final class AreaMinerMod implements ModInitializer {
    static final Logger LOG = LoggerFactory.getLogger("AreaMiner");
    private static AreaMinerMod instance;
    private final Map<UUID, Mode> modes = new HashMap<>();
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, Settings> preferences = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    // Manual breaks that reached Fabric's AFTER event (the block was actually removed).
    private final Set<UUID> broken = new HashSet<>();
    private final Set<UUID> internal = new HashSet<>();
    private List<Move> moves = new ArrayList<>();
    private ServerConfig config;
    private PlayerStore store;
    private final FabricPreview preview = new FabricPreview(this);
    private final FabricLink link = new FabricLink(this);
    private MinecraftServer server;
    private int ticks;
    // The AreaMiner-driven break currently running inside ServerPlayerGameMode.destroyBlock.
    private UUID activePlayer;
    private BlockPos activePos;
    private ResourceKey<Level> activeLevel;
    private final List<ItemEntity> captured = new ArrayList<>();

    private record Pending(BlockPos pos, Mode mode, List<Shape.Offset> offsets, Map<Shape.Offset, Block> expected, int slot, ItemStack tool) {}
    private record Move(UUID player, ResourceKey<Level> level, List<ItemEntity> items) {}
    private static final class Job {
        final UUID player;
        final ResourceKey<Level> level;
        final Mode mode;
        final int slot, x, y, z;
        final List<Shape.Offset> offsets;
        final Map<Shape.Offset, Block> expected;
        ItemStack tool;
        int index;
        Job(ServerPlayer p, BlockPos origin, Mode mode, List<Shape.Offset> offsets, Map<Shape.Offset, Block> expected) {
            player = p.getUUID(); level = p.level().dimension();
            this.mode = mode; slot = p.getInventory().getSelectedSlot();
            x = origin.getX(); y = origin.getY(); z = origin.getZ();
            this.offsets = offsets; this.expected = expected;
            tool = p.getMainHandItem().copy();
        }
    }

    @Override public void onInitialize() {
        instance = this;
        PayloadTypeRegistry.serverboundPlay().register(SettingsPayload.TYPE, SettingsPayload.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(SettingsPayload.TYPE, SettingsPayload.CODEC);
        link.register();
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s; readConfig();
            store = new PlayerStore(FabricLoader.getInstance().getConfigDir().resolve("areaminer").resolve("players.json"));
            LOG.info("AreaMiner enabled: {} allowed materials, {} modes.", config.materials.size(), Mode.values().length - 1);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> {
            preview.forgetAll(); jobs.clear(); pending.clear(); broken.clear(); modes.clear(); preferences.clear(); moves.clear(); link.clear();
            server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (server == null) return;
            // Manual breaks finish synchronously inside destroyBlock; nothing should still be open here.
            pending.clear(); broken.clear(); endBreak();
            processMoves();
            tick();
            if (++ticks % 5 == 0) preview.update(s);
            if (ticks % 2 == 0) preview.animate(s);
        });
        PlayerBlockBreakEvents.BEFORE.register(this::beforeBreak);
        PlayerBlockBreakEvents.AFTER.register(this::afterBreak);
        PlayerBlockBreakEvents.CANCELED.register((level, player, pos, state, blockEntity) -> {
            if (!internal.contains(player.getUUID())) { pending.remove(player.getUUID()); broken.remove(player.getUUID()); endBreak(); }
        });
        // Block.popResource spawns drops inside the broken block's cell; neighbours that pop off land in their own cells.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (activePlayer != null && entity instanceof ItemEntity item && level.dimension() == activeLevel
                && new AABB(activePos).contains(item.position())) captured.add(item);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
            ServerPlayer p = handler.player;
            modes.remove(p.getUUID());
            if (config != null && config.data.showJoinHelp && usePermission(p)) {
                p.sendSystemMessage(Component.literal(messages(p).get("join")).withStyle(ChatFormatting.AQUA));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, s) -> {
            UUID id = handler.player.getUUID();
            modes.remove(id); jobs.remove(id); pending.remove(id); broken.remove(id); preview.forget(id); preferences.remove(id); link.forget(id);
        });
        ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register((player, origin, destination) -> {
            jobs.remove(player.getUUID()); preview.clear(player);
        });
        // The client keeps entities when respawning in the same dimension, so remove the cubes explicitly.
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            jobs.remove(newPlayer.getUUID()); preview.clear(newPlayer);
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> registerCommand(dispatcher));
    }

    private void readConfig() {
        config = ServerConfig.load(FabricLoader.getInstance().getConfigDir().resolve("areaminer").resolve("config.json"));
        config.warnings.forEach(LOG::warn);
    }

    // ---- shared helpers (also used by FabricPreview / FabricLink / mixins) ----

    static Messages messages(ServerPlayer p) { return Messages.of(p.clientInformation().language()); }
    /** Chat line in the player's language: prefix + message from the catalog. */
    static void tell(ServerPlayer p, String key, Object... args) { say(p, messages(p).get(key, args)); }
    private static void say(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal(messages(p).get("prefix") + " " + text).withStyle(ChatFormatting.AQUA));
    }
    /** The key toggle shows only the mode; a command that also set the range shows it too. */
    void select(ServerPlayer p, Mode mode) { select(p, mode, false); }
    void select(ServerPlayer p, Mode mode, boolean withRange) {
        modes.put(p.getUUID(), mode); jobs.remove(p.getUUID());
        preview.clear(p);
        say(p, withRange ? settings(p).label(mode, messages(p)) : mode.label(messages(p)));
    }
    Mode mode(ServerPlayer p) { return modes.getOrDefault(p.getUUID(), Mode.OFF); }
    Settings settings(ServerPlayer p) { return preferences.computeIfAbsent(p.getUUID(), store::load); }
    /** Where everyone's drops go when the server decides it (config "drops"), or null when each player chooses. */
    Boolean forcedDrops() { return config == null ? null : config.dropsAtFeet; }
    private boolean dropsAtFeet(ServerPlayer p) { Boolean forced = forcedDrops(); return forced != null ? forced : settings(p).dropsAtFeet; }
    private String dropsLabel(ServerPlayer p, Messages m) {
        String label = m.get(dropsAtFeet(p) ? "drops.feet" : "drops.block");
        return forcedDrops() == null ? label : m.get("drops.server", label);
    }
    // Same nodes and defaults as plugin.yml; LuckPerms etc. can override them through fabric-permissions-api.
    boolean usePermission(ServerPlayer p) { return Permissions.check(p, "areaminer.use", true); }
    private boolean eligible(ServerPlayer p) {
        GameType type = p.gameMode.getGameModeForPlayer();
        return usePermission(p) && toolAllowed(p) && (type == GameType.SURVIVAL || type == GameType.CREATIVE);
    }
    boolean previewAllowed(ServerPlayer p) {
        return config != null && config.data.previewEnabled && eligible(p) && mode(p) != Mode.OFF && settings(p).highlight;
    }
    boolean targetMaterial(BlockState state) { return config != null && config.materials.contains(state.getBlock()); }
    private boolean ore(BlockState state) { return config != null && config.data.veinMining && config.ores.contains(state.getBlock()); }
    /** Vein mining only with a tool that makes the ore drop; otherwise a whole vein would vanish without drops. */
    private static boolean veinTool(ServerPlayer p, BlockState state) { return p.isCreative() || p.hasCorrectToolForDrops(state); }
    /** A block that starts area mining (stone, soil...) or vein mining (ores, with a suitable tool). */
    boolean minable(ServerPlayer p, BlockState state) { return targetMaterial(state) || (ore(state) && veinTool(p, state)); }
    /**
     * Blocks to mine around b with the block each must still be: the connected vein for an ore, otherwise the
     * mode's area filtered to the configured materials. Shared by mining and the highlight.
     */
    Map<Shape.Offset, Block> plan(ServerPlayer p, BlockPos b) {
        Map<Shape.Offset, Block> expected = new LinkedHashMap<>();
        ServerLevel world = p.level();
        BlockState origin = world.getBlockState(b);
        if (ore(origin)) {
            if (!veinTool(p, origin)) return expected;
            String family = Vein.family(BuiltInRegistries.BLOCK.getKey(origin.getBlock()).toString());
            // veinMax counts the broken ore too.
            for (Shape.Offset o : Vein.find(config.veinMax - 1, (dx, dy, dz) -> {
                BlockState s = loadedState(world, b.offset(dx, dy, dz));
                return s != null && ore(s) && Vein.family(BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString()).equals(family);
            })) expected.put(o, world.getBlockState(b.offset(o.x(), o.y(), o.z())).getBlock());
            return expected;
        }
        for (Shape.Offset o : offsets(p, b)) {
            BlockState s = loadedState(world, b.offset(o.x(), o.y(), o.z()));
            if (s != null && targetMaterial(s)) expected.put(o, s.getBlock());
        }
        return expected;
    }
    /** Block state, or null outside the build height or in an unloaded chunk (never loads chunks). */
    private static BlockState loadedState(ServerLevel world, BlockPos pos) {
        if (world.isOutsideBuildHeight(pos.getY()) || !world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;
        return world.getBlockState(pos);
    }
    private boolean toolAllowed(ServerPlayer p) { return (settings(p).toolMask & toolBit(p.getMainHandItem())) != 0; }
    static int toolBit(ItemStack stack) {
        if (stack.isEmpty()) return 32;
        String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        if (name.endsWith("_pickaxe")) return 1;
        if (name.endsWith("_shovel")) return 2;
        if (name.endsWith("_axe")) return 4;
        if (name.endsWith("_hoe")) return 8;
        return stack.is(Items.SHEARS) ? 16 : 64;
    }
    static boolean inside(ServerLevel level, BlockPos pos) {
        return !level.isOutsideBuildHeight(pos.getY()) && level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
            && level.getWorldBorder().isWithinBounds(pos);
    }
    static BlockHitResult rayTrace(ServerPlayer p, double distance) {
        Vec3 eye = p.getEyePosition();
        return p.level().clip(new ClipContext(eye, eye.add(p.getViewVector(1f).scale(distance)),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
    }
    private Direction face(ServerPlayer p, BlockPos b) {
        BlockHitResult hit = rayTrace(p, 8);
        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(b)) return hit.getDirection();
        Vec3 v = p.getEyePosition().subtract(Vec3.atCenterOf(b));
        double x = Math.abs(v.x), y = Math.abs(v.y), z = Math.abs(v.z);
        if (y >= x && y >= z) return v.y >= 0 ? Direction.UP : Direction.DOWN;
        if (x >= z) return v.x >= 0 ? Direction.EAST : Direction.WEST;
        return v.z >= 0 ? Direction.SOUTH : Direction.NORTH;
    }
    List<Shape.Offset> offsets(ServerPlayer p, BlockPos b) {
        Mode selectedMode = mode(p);
        Settings settings = settings(p);
        if (selectedMode == Mode.STAIRS) {
            Vec3 direction = p.getViewVector(1f);
            int dx = 0, dz = 0;
            if (Math.abs(direction.x) >= Math.abs(direction.z)) dx = direction.x >= 0 ? 1 : -1;
            else dz = direction.z >= 0 ? 1 : -1;
            return Shape.stairs(dx, dz, p.getXRot() < 0, settings.stairWidth, settings.stairHeight, settings.stairSteps);
        }
        Vec3 look = p.getViewVector(1f);
        if (selectedMode == Mode.FLATTEN) {
            return Shape.flatten(settings.flattenWidth, settings.flattenDepth, settings.flattenHeight,
                p.blockPosition().getY() - b.getY(), Math.abs(look.x) >= Math.abs(look.z));
        }
        Direction f = face(p, b);
        boolean plane = selectedMode == Mode.PLANE;
        return Shape.box(plane ? settings.planeWidth : settings.cubeWidth, plane ? settings.planeHeight : settings.cubeHeight,
            plane ? 1 : settings.cubeDepth, f.getStepX(), f.getStepY(), f.getStepZ(), p.blockPosition().getY() - b.getY(),
            Math.abs(look.x) >= Math.abs(look.z));
    }

    // ---- breaking ----

    private boolean beforeBreak(Level level, Player player, BlockPos b, BlockState state, net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!(player instanceof ServerPlayer p) || !(level instanceof ServerLevel world) || config == null) return true;
        UUID id = p.getUUID();
        Mode mode = mode(p);
        if (internal.contains(id) || mode == Mode.OFF || !eligible(p) || !minable(p, state)) return true;
        Map<Shape.Offset, Block> expected = plan(p, b);
        List<Shape.Offset> offsets = new ArrayList<>(expected.keySet());
        pending.put(id, new Pending(b.immutable(), mode, offsets, expected, p.getInventory().getSelectedSlot(), p.getMainHandItem().copy()));
        beginBreak(p, b);
        return true;
    }
    private void afterBreak(Level level, Player player, BlockPos b, BlockState state, net.minecraft.world.level.block.entity.BlockEntity be) {
        if (!(player instanceof ServerPlayer p) || internal.contains(p.getUUID())) return;
        Pending job = pending.get(p.getUUID());
        if (job != null && job.pos().equals(b)) broken.add(p.getUUID());
    }
    /**
     * Called from PlayerGameModeMixin when destroyBlock returns, after tool wear and drops.
     * Fabric's AFTER fires before both, so the job (and its tool snapshot) is created here.
     */
    public static void destroyed(ServerPlayer p, BlockPos b, boolean success) {
        AreaMinerMod mod = instance;
        if (mod == null) return;
        // Covers both manual and area breaks; the highlight cube must not outlive its block.
        if (success) mod.preview.removeAt(p, b);
        if (mod.internal.contains(p.getUUID())) return;
        Pending job = mod.pending.remove(p.getUUID());
        boolean wasBroken = mod.broken.remove(p.getUUID());
        if (job == null || !job.pos().equals(b)) return;
        if (!success || !wasBroken) { mod.endBreak(); return; }
        mod.finishBreak(p);
        // A second manual break supersedes the previous queued operation.
        mod.jobs.remove(p.getUUID());
        BlockState now = p.level().getBlockState(b);
        if (!mod.eligible(p) || mod.mode(p) != job.mode() || (!now.isAir() && now.getFluidState().isEmpty())
            || p.getInventory().getSelectedSlot() != job.slot() || !p.getMainHandItem().is(job.tool().getItem())) return;
        mod.jobs.put(p.getUUID(), new Job(p, b, job.mode(), job.offsets(), job.expected()));
    }
    private void beginBreak(ServerPlayer p, BlockPos pos) {
        activePlayer = p.getUUID(); activePos = pos.immutable(); activeLevel = p.level().dimension(); captured.clear();
    }
    private void endBreak() { activePlayer = null; activePos = null; activeLevel = null; captured.clear(); }
    /** Ends the active break and queues its drops to move to the player's feet on the next tick. */
    private void finishBreak(ServerPlayer p) {
        if (activePlayer != null && activePlayer.equals(p.getUUID()) && dropsAtFeet(p) && !captured.isEmpty()) {
            moves.add(new Move(p.getUUID(), activeLevel, new ArrayList<>(captured)));
        }
        endBreak();
    }
    private void processMoves() {
        if (moves.isEmpty()) return;
        List<Move> due = moves; moves = new ArrayList<>();
        for (Move move : due) {
            ServerPlayer p = server.getPlayerList().getPlayer(move.player());
            if (p == null || p.level().dimension() != move.level()) continue;
            Vec3 feet = p.position().add(0, .1, 0);
            for (ItemEntity item : move.items()) {
                if (!item.isRemoved() && item.level().dimension() == move.level()) {
                    item.teleportTo(feet.x, feet.y, feet.z); item.setDeltaMovement(Vec3.ZERO);
                }
            }
        }
    }
    /** Called from ItemStackMixin after Unbreaking has been applied. */
    public static int scaleDamage(ItemStack stack, ServerPlayer player, int amount) {
        AreaMinerMod mod = instance;
        if (mod == null || player == null || amount <= 0 || mod.activePlayer == null || !mod.activePlayer.equals(player.getUUID())) return amount;
        if (stack != player.getMainHandItem()) return amount;
        return mod.settings(player).scaledDamage(amount);
    }
    /** Called from PlayerActionMixin for the sneak + swap-hands toggle used without the client mod. Returns true to cancel the swap. */
    public static boolean swapHands(ServerPlayer p) {
        AreaMinerMod mod = instance;
        if (mod == null || mod.config == null || p.isSpectator() || !mod.usePermission(p) || mod.link.connected(p)) return false;
        if (mod.config.data.requireSneaking && !p.isShiftKeyDown()) return false;
        mod.select(p, mod.mode(p).next());
        return true;
    }

    private void tick() {
        int budget = config.perTick;
        for (Job job : new ArrayList<>(jobs.values())) {
            if (budget <= 0) break;
            ServerPlayer p = server.getPlayerList().getPlayer(job.player);
            if (p == null || !p.isAlive() || !eligible(p) || p.level().dimension() != job.level
                || modes.getOrDefault(job.player, Mode.OFF) != job.mode
                || p.position().distanceToSqr(job.x + .5, job.y + .5, job.z + .5) > 1024
                || p.getInventory().getSelectedSlot() != job.slot
                || !p.getMainHandItem().is(job.tool.getItem())) {
                jobs.remove(job.player, job); continue;
            }
            int count = 0;
            while (job.index < job.offsets.size() && count++ < config.perPlayer && budget-- > 0) {
                if (jobs.get(job.player) != job) break;
                if (p.hasDisconnected() || !p.isAlive() || !eligible(p) || p.level().dimension() != job.level
                    || p.getInventory().getSelectedSlot() != job.slot
                    || !p.getMainHandItem().is(job.tool.getItem())) {
                    jobs.remove(job.player, job); break;
                }
                Shape.Offset o = job.offsets.get(job.index++);
                Block expected = job.expected.get(o);
                if (expected == null) continue;
                ServerLevel w = p.level();
                BlockPos pos = new BlockPos(job.x + o.x(), job.y + o.y(), job.z + o.z());
                // mayInteract covers spawn protection, which vanilla checks before destroyBlock, not inside it (non-ops only).
                if (!inside(w, pos) || !w.getBlockState(pos).is(expected) || !p.mayInteract(w, pos)) continue;
                ItemStack before = p.getMainHandItem().copy();
                internal.add(job.player);
                beginBreak(p, pos);
                // destroyBlock fires PlayerBlockBreakEvents, so protection mods can veto each block.
                try { p.gameMode.destroyBlock(pos); }
                finally { internal.remove(job.player); finishBreak(p); }
                ItemStack after = p.getMainHandItem();
                if (!after.is(before.getItem()) || p.getInventory().getSelectedSlot() != job.slot) {
                    tell(p, "stopped.tool");
                    jobs.remove(job.player, job); break;
                }
                job.tool = after.copy();
            }
            if (job.index >= job.offsets.size()) jobs.remove(job.player, job);
        }
    }

    // ---- settings ----

    private boolean changed(ServerPlayer p) {
        Settings s = settings(p);
        jobs.remove(p.getUUID()); preview.clear(p);
        try { store.save(p.getUUID(), s); link.changed(p); return true; }
        catch (Exception e) { LOG.error("Cannot save player preferences: {}", e.getMessage()); tell(p, "error.save"); return false; }
    }
    /** The player's settings as the client mod shows them: drops as the server decides, when it does. */
    Protocol.State snapshot(ServerPlayer p) {
        return Protocol.State.of(settings(p)).withFeet(dropsAtFeet(p));
    }
    boolean applySnapshot(ServerPlayer p, Protocol.State state) {
        Settings s = settings(p);
        // While the server decides the drops, the player's own choice is kept for when it no longer does.
        boolean own = s.dropsAtFeet;
        state.applyTo(s);
        if (forcedDrops() != null) s.dropsAtFeet = own;
        return changed(p);
    }

    // ---- /areaminer ----

    private void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("areaminer")
            .executes(c -> command(c.getSource(), new String[0]))
            .then(Commands.argument("args", StringArgumentType.greedyString())
                .suggests(this::suggest)
                .executes(c -> command(c.getSource(), StringArgumentType.getString(c, "args").trim().split("\\s+")))));
    }
    private static Messages sourceMessages(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer p ? messages(p) : Messages.ENGLISH;
    }
    private static boolean admin(CommandSourceStack source) {
        return Permissions.check(source, "areaminer.admin", PermissionLevel.GAMEMASTERS);
    }
    private String toggleKeys(Messages m) {
        return m.get(config == null || config.data.requireSneaking ? "toggle.sneak" : "toggle.swap");
    }
    private void help(CommandSourceStack source) {
        Messages m = sourceMessages(source);
        for (String line : CommandText.help(toggleKeys(m), m, config != null && config.data.veinMining)) source.sendSystemMessage(Component.literal(line));
    }
    private int command(CommandSourceStack source, String[] args) {
        if (args.length == 1 && args[0].isEmpty()) args = new String[0];
        String sub = args.length == 0 ? "next" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!admin(source)) { source.sendSystemMessage(Component.literal(sourceMessages(source).get("error.permission"))); return 1; }
            readConfig(); jobs.clear();
            if (server != null) {
                preview.clear(server);
                // The drops setting may have changed: the client mods show it (and whether it can be changed).
                for (ServerPlayer p : server.getPlayerList().getPlayers()) link.changed(p);
            }
            source.sendSystemMessage(Component.literal(sourceMessages(source).get("reloaded"))); return 1;
        }
        if (sub.equals("help")) { help(source); return 1; }
        if (!(source.getEntity() instanceof ServerPlayer p)) { source.sendSystemMessage(Component.literal(Messages.ENGLISH.get("error.in.game"))); return 1; }
        if (!usePermission(p)) { tell(p, "error.permission"); return 1; }
        Messages m = messages(p);
        Settings s = settings(p);
        try {
            switch (sub) {
                case "next" -> { require(args, 1); select(p, mode(p).next()); }
                case "off" -> { require(args, 1); select(p, Mode.OFF); }
                case "plane", "平面" -> {
                    if (args.length == 2 || args.length == 3) {
                        int w = Integer.parseInt(args[1]), h = args.length == 3 ? Integer.parseInt(args[2]) : w;
                        if (!Settings.validPlane(w, h)) throw new IllegalArgumentException();
                        s.planeWidth = w; s.planeHeight = h; changed(p);
                    } else require(args, 1);
                    select(p, Mode.PLANE, args.length > 1);
                }
                case "cube", "立体" -> {
                    if (args.length == 2 || args.length == 4) {
                        int w = Integer.parseInt(args[1]), h = args.length == 4 ? Integer.parseInt(args[2]) : w, d = args.length == 4 ? Integer.parseInt(args[3]) : w;
                        if (!Settings.validCube(w, h, d)) throw new IllegalArgumentException();
                        s.cubeWidth = w; s.cubeHeight = h; s.cubeDepth = d; changed(p);
                    } else require(args, 1);
                    select(p, Mode.CUBE, args.length > 1);
                }
                case "stairs", "階段" -> {
                    if (args.length == 4) {
                        int w = Integer.parseInt(args[1]), h = Integer.parseInt(args[2]), n = Integer.parseInt(args[3]);
                        if (!Settings.validStairs(w, h, n)) throw new IllegalArgumentException();
                        s.stairWidth = w; s.stairHeight = h; s.stairSteps = n; changed(p);
                    } else require(args, 1);
                    select(p, Mode.STAIRS, args.length > 1);
                }
                case "flatten", "整地" -> {
                    if (args.length == 2 || args.length == 4) {
                        int w = Integer.parseInt(args[1]), d = args.length == 4 ? Integer.parseInt(args[2]) : w, h = args.length == 4 ? Integer.parseInt(args[3]) : w;
                        if (!Settings.validFlatten(w, d, h)) throw new IllegalArgumentException();
                        s.flattenWidth = w; s.flattenDepth = d; s.flattenHeight = h; changed(p);
                    } else require(args, 1);
                    select(p, Mode.FLATTEN, args.length > 1);
                }
                case "status" -> {
                    require(args, 1);
                    tell(p, "status.modes", s.label(mode(p), m), s.label(Mode.PLANE, m), s.label(Mode.CUBE, m), s.label(Mode.STAIRS, m), s.label(Mode.FLATTEN, m));
                    tell(p, "status.options", s.highlight ? "ON" : "OFF", dropsLabel(p, m), s.durabilityLabel(m), s.toolMask, s.colorLabel(m));
                }
                case "highlight" -> {
                    require(args, 2); String value = args[1].toLowerCase(Locale.ROOT);
                    if (!value.equals("on") && !value.equals("off")) throw new IllegalArgumentException();
                    s.highlight = value.equals("on"); changed(p); tell(p, "set.highlight", s.highlight ? "ON" : "OFF");
                }
                case "color" -> {
                    // color gaming 5: the gaming highlight at speed 5.
                    if (args.length != 2 && args.length != 3) throw new IllegalArgumentException();
                    int color = Settings.parseColor(args[1]);
                    if (args.length == 3) {
                        int speed = Integer.parseInt(args[2]);
                        if (color != Settings.GAMING || !Settings.selectableGamingSpeed(speed)) throw new IllegalArgumentException();
                        s.gamingSpeed = speed;
                    }
                    s.highlightColor = color; changed(p); tell(p, "set.color", s.colorLabel(m));
                }
                case "drops" -> {
                    require(args, 2); String value = args[1].toLowerCase(Locale.ROOT);
                    if (!value.equals("feet") && !value.equals("block")) throw new IllegalArgumentException();
                    if (forcedDrops() != null) { tell(p, "drops.locked", m.get(forcedDrops() ? "drops.feet" : "drops.block")); return 1; }
                    s.dropsAtFeet = value.equals("feet"); changed(p); tell(p, "set.drops", m.get(s.dropsAtFeet ? "drops.feet" : "drops.block"));
                }
                case "tools" -> {
                    // "all" = the five real tools, like the settings screen; hand/other items only via the number.
                    require(args, 2); int mask = args[1].equalsIgnoreCase("all") ? s.toolMask | Settings.DEFAULT_TOOLS : Integer.parseInt(args[1]);
                    if (mask < 0 || mask > 127) throw new IllegalArgumentException();
                    s.toolMask = mask; changed(p); tell(p, "set.tools", mask);
                }
                case "durability" -> {
                    require(args, 2); double value = switch (args[1].toLowerCase(Locale.ROOT)) {
                        case "normal", "1" -> 1; case "half", "0.5" -> .5; case "none", "0" -> 0;
                        default -> throw new IllegalArgumentException();
                    };
                    s.durability = value; s.damageRemainder = 0; changed(p); tell(p, "set.durability", s.durabilityLabel(m));
                }
                default -> throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) { tell(p, "error.invalid"); }
        return 1;
    }
    private static void require(String[] args, int length) {
        if (args.length != length && !(length == 1 && args.length == 0)) throw new IllegalArgumentException();
    }
    private CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        String[] args = remaining.split(" ", -1);
        String last = args[args.length - 1];
        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + remaining.length() - last.length());
        CommandText.suggestions(args, admin(context.getSource())).forEach(offset::suggest);
        return offset.buildFuture();
    }
}

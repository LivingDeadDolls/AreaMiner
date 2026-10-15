package areaminer.paper;

import areaminer.CommandText;
import areaminer.Messages;
import areaminer.Mode;
import areaminer.Settings;
import areaminer.Shape;
import areaminer.Vein;

import java.util.*;
import areaminer.protocol.Protocol;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.entity.Item;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

public final class AreaMiner extends JavaPlugin implements Listener, TabExecutor {
    private static final Mode[] MODES = Mode.values();
    private final Map<UUID, Mode> modes = new HashMap<>();
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Set<UUID> internal = new HashSet<>();
    private final Set<Material> materials = EnumSet.noneOf(Material.class);
    private final Set<Material> ores = EnumSet.noneOf(Material.class);
    private boolean veinMining;
    private int veinMax;
    private int perPlayer, perTick;
    private Preview preview;
    private ClientLink clientLink;
    private final Map<UUID, Settings> preferences = new HashMap<>();
    private final Map<UUID, Block> originalBreaks = new HashMap<>();
    private YamlConfiguration playerData;
    private File playerFile;
    private boolean playersReadOnly;

    private static final class Job {
        final UUID player, world;
        final Mode mode;
        final int slot, x, y, z;
        final List<Shape.Offset> offsets;
        final Map<Shape.Offset, Material> expected;
        ItemStack tool;
        int index;
        Job(Player p, Block origin, Mode mode, List<Shape.Offset> offsets,
            Map<Shape.Offset, Material> expected) {
            player = p.getUniqueId(); world = origin.getWorld().getUID();
            this.mode = mode; slot = p.getInventory().getHeldItemSlot();
            x = origin.getX(); y = origin.getY(); z = origin.getZ();
            this.offsets = offsets; this.expected = expected;
            tool = p.getInventory().getItemInMainHand().clone();
        }
    }

    @Override public void onEnable() {
        saveDefaultConfig(); readConfig();
        playerFile = new File(getDataFolder(), "players.yml");
        playerData = loadPlayers();
        preview = new Preview(this);
        clientLink = new ClientLink(this);
        preview.clearStale();
        getServer().getScheduler().runTaskTimer(this, () -> preview.update(), 1L, 5L);
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("areaminer")).setExecutor(this);
        Objects.requireNonNull(getCommand("areaminer")).setTabCompleter(this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("AreaMiner enabled: " + materials.size() + " allowed materials, " + (MODES.length - 1) + " modes.");
    }

    @Override public void onDisable() {
        if (preview != null) preview.clear();
        jobs.clear();
    }

    private YamlConfiguration loadPlayers() {
        YamlConfiguration data = new YamlConfiguration();
        if (!playerFile.exists()) return data;
        try { data.load(playerFile); return data; }
        catch (IOException | InvalidConfigurationException e) {
            // Keep the unreadable file so the next save does not silently discard everyone's settings.
            File backup = new File(playerFile.getParentFile(), playerFile.getName() + ".broken-" + System.currentTimeMillis());
            if (playerFile.renameTo(backup)) getLogger().warning("Cannot read players.yml, moved it to " + backup.getName() + ": " + e.getMessage());
            else {
                try { Files.copy(playerFile.toPath(), backup.toPath()); getLogger().warning("Cannot read players.yml, copied it to " + backup.getName() + ": " + e.getMessage()); }
                catch (IOException copyFailed) {
                    // Saving now would overwrite everyone's settings with an empty file.
                    playersReadOnly = true;
                    getLogger().severe("Cannot read or back up players.yml; player settings will not be saved until restart: " + e.getMessage());
                }
            }
            return new YamlConfiguration();
        }
    }
    private void readConfig() {
        materials.clear();
        for (String name : getConfig().getStringList("materials")) {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isBlock() && !m.isAir() && m.getHardness() >= 0) materials.add(m);
            else getLogger().warning("Ignored invalid/unbreakable material: " + name);
        }
        ores.clear();
        for (String name : getConfig().getStringList("ores")) {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isBlock() && !m.isAir() && m.getHardness() >= 0) ores.add(m);
            else getLogger().warning("Ignored invalid/unbreakable ore: " + name);
        }
        veinMining = getConfig().getBoolean("vein-mining", true);
        veinMax = Math.max(1, Math.min(1000, getConfig().getInt("vein-max-blocks", 64)));
        perPlayer = Math.max(1, Math.min(1000, getConfig().getInt("blocks-per-player-per-tick", 64)));
        perTick = Math.max(1, Math.min(4000, getConfig().getInt("blocks-total-per-tick", 256)));
    }

    static Messages messages(CommandSender sender) {
        return sender instanceof Player p ? Messages.of(p.locale().toString()) : Messages.ENGLISH;
    }
    /** Chat line in the player's language: prefix + message from the catalog. */
    void tell(Player p, String key, Object... args) { say(p, messages(p).get(key, args)); }
    private void say(Player p, String text) {
        p.sendMessage(Component.text(messages(p).get("prefix") + " " + text, NamedTextColor.AQUA));
    }
    /** The key toggle shows only the mode; a command that also set the range shows it too. */
    private void select(Player p, Mode mode) { select(p, mode, false); }
    private void select(Player p, Mode mode, boolean withRange) {
        modes.put(p.getUniqueId(), mode); jobs.remove(p.getUniqueId());
        preview.clear(p.getUniqueId());
        say(p, withRange ? settings(p).label(mode, messages(p)) : mode.label(messages(p)));
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void swap(PlayerSwapHandItemsEvent event) {
        Player p = event.getPlayer();
        if (!p.hasPermission("areaminer.use") || clientLink.connected(p)) return;
        if (getConfig().getBoolean("require-sneaking", true) && !p.isSneaking()) return;
        event.setCancelled(true);
        select(p, mode(p).next());
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        modes.remove(event.getPlayer().getUniqueId());
        if (getConfig().getBoolean("show-join-help", true) && event.getPlayer().hasPermission("areaminer.use")) {
            event.getPlayer().sendMessage(Component.text(messages(event.getPlayer()).get("join"), NamedTextColor.AQUA));
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId(); modes.remove(id); jobs.remove(id); preview.clear(id); originalBreaks.remove(id); preferences.remove(id); clientLink.forget(event.getPlayer());
    }

    private boolean eligible(Player p) {
        return p.hasPermission("areaminer.use") && toolAllowed(p) && (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.CREATIVE);
    }
    private BlockFace face(Player p, Block b) {
        RayTraceResult hit = p.rayTraceBlocks(8, FluidCollisionMode.NEVER);
        if (hit != null && b.equals(hit.getHitBlock()) && hit.getHitBlockFace() != null) return hit.getHitBlockFace();
        Vector v = p.getEyeLocation().toVector().subtract(b.getLocation().add(.5, .5, .5).toVector());
        double x = Math.abs(v.getX()), y = Math.abs(v.getY()), z = Math.abs(v.getZ());
        if (y >= x && y >= z) return v.getY() >= 0 ? BlockFace.UP : BlockFace.DOWN;
        if (x >= z) return v.getX() >= 0 ? BlockFace.EAST : BlockFace.WEST;
        return v.getZ() >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    boolean previewAllowed(Player p) {
        return getConfig().getBoolean("preview-enabled", true) && eligible(p)
            && mode(p) != Mode.OFF && settings(p).highlight;
    }
    private boolean ore(Block block) { return veinMining && ores.contains(block.getType()); }
    /** Vein mining only with a tool that makes the ore drop; otherwise a whole vein would vanish without drops. */
    private boolean veinTool(Player p, Block b) {
        return p.getGameMode() == GameMode.CREATIVE || b.isPreferredTool(p.getInventory().getItemInMainHand());
    }
    /** A block that starts area mining (stone, soil...) or vein mining (ores, with a suitable tool). */
    boolean minable(Player p, Block block) { return materials.contains(block.getType()) || (ore(block) && veinTool(p, block)); }
    /**
     * Blocks to mine around b with the material each must still have: the connected vein for an ore,
     * otherwise the mode's area filtered to the configured materials. Shared by mining and the highlight.
     */
    Map<Shape.Offset, Material> plan(Player p, Block b) {
        Map<Shape.Offset, Material> expected = new LinkedHashMap<>();
        World w = b.getWorld();
        if (ore(b)) {
            if (!veinTool(p, b)) return expected;
            String family = Vein.family(b.getType().getKey().toString());
            // veinMax counts the broken ore too.
            for (Shape.Offset o : Vein.find(veinMax - 1, (dx, dy, dz) -> {
                Material m = loadedType(w, b.getX() + dx, b.getY() + dy, b.getZ() + dz);
                return m != null && ores.contains(m) && Vein.family(m.getKey().toString()).equals(family);
            })) expected.put(o, w.getBlockAt(b.getX() + o.x(), b.getY() + o.y(), b.getZ() + o.z()).getType());
            return expected;
        }
        for (Shape.Offset o : offsets(p, b)) {
            Material m = loadedType(w, b.getX() + o.x(), b.getY() + o.y(), b.getZ() + o.z());
            if (m != null && materials.contains(m)) expected.put(o, m);
        }
        return expected;
    }
    /** Block type, or null outside the build height or in an unloaded chunk (never loads chunks). */
    private static Material loadedType(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) return null;
        return w.getBlockAt(x, y, z).getType();
    }
    List<Shape.Offset> offsets(Player p, Block b) {
        BlockFace f = face(p, b);
        Mode selectedMode = mode(p);
        Settings settings = settings(p);
        List<Shape.Offset> offsets;
        if (selectedMode == Mode.STAIRS) {
            Vector direction = p.getEyeLocation().getDirection();
            int dx = 0, dz = 0;
            if (Math.abs(direction.getX()) >= Math.abs(direction.getZ())) {
                dx = direction.getX() >= 0 ? 1 : -1;
            } else {
                dz = direction.getZ() >= 0 ? 1 : -1;
            }
            offsets = Shape.stairs(dx, dz, p.getEyeLocation().getPitch() < 0, settings.stairWidth, settings.stairHeight, settings.stairSteps);
        } else if (selectedMode == Mode.FLATTEN) {
            Vector look = p.getEyeLocation().getDirection();
            offsets = Shape.flatten(settings.flattenWidth, settings.flattenDepth, settings.flattenHeight,
                p.getLocation().getBlockY() - b.getY(), Math.abs(look.getX()) >= Math.abs(look.getZ()));
        } else {
            Vector look = p.getEyeLocation().getDirection();
            boolean plane = selectedMode == Mode.PLANE;
            offsets = Shape.box(plane ? settings.planeWidth : settings.cubeWidth, plane ? settings.planeHeight : settings.cubeHeight,
                plane ? 1 : settings.cubeDepth, f.getModX(), f.getModY(), f.getModZ(), p.getLocation().getBlockY() - b.getY(),
                Math.abs(look.getX()) >= Math.abs(look.getZ()));
        }
        return offsets;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        Player p = event.getPlayer(); UUID id = p.getUniqueId();
        // Manual and area breaks both pass here; the highlight cube must not outlive its block.
        preview.removeAt(id, event.getBlock());
        Mode mode = mode(p);
        Block b = event.getBlock();
        if (internal.contains(id) || mode == Mode.OFF || !eligible(p) || !minable(p, b)) return;
        originalBreaks.put(id, b);
        // A second manual break supersedes the previous queued operation.
        jobs.remove(id);
        Map<Shape.Offset, Material> expected = plan(p, b);
        List<Shape.Offset> offsets = new ArrayList<>(expected.keySet());
        ItemStack startingTool = p.getInventory().getItemInMainHand().clone();
        int slot = p.getInventory().getHeldItemSlot();
        // Run after vanilla handles the original block and durability. Keep the final event cancellation state.
        getServer().getScheduler().runTask(this, () -> {
            originalBreaks.remove(id, b);
            if (event.isCancelled() || !p.isOnline() || !eligible(p)
                || modes.getOrDefault(id, Mode.OFF) != mode || (!b.getType().isAir() && !b.isLiquid())
                || p.getInventory().getHeldItemSlot() != slot
                || p.getInventory().getItemInMainHand().getType() != startingTool.getType()) return;
            jobs.put(id, new Job(p, b, mode, offsets, expected));
        });
    }

    private void tick() {
        int budget = perTick;
        // Remove/process snapshots so cancellation callbacks can safely change the map.
        for (Job job : new ArrayList<>(jobs.values())) {
            if (budget <= 0) break;
            Player p = getServer().getPlayer(job.player);
            if (p == null || p.isDead() || !eligible(p) || !p.getWorld().getUID().equals(job.world)
                || modes.getOrDefault(job.player, Mode.OFF) != job.mode
                || p.getLocation().distanceSquared(new Location(p.getWorld(), job.x+.5, job.y+.5, job.z+.5)) > 1024
                || p.getInventory().getHeldItemSlot() != job.slot
                || p.getInventory().getItemInMainHand().getType() != job.tool.getType()) {
                jobs.remove(job.player, job); continue;
            }
            int count = 0;
            while (job.index < job.offsets.size() && count++ < perPlayer && budget-- > 0) {
                if (jobs.get(job.player) != job) break;
                if (!p.isOnline() || p.isDead() || !eligible(p) || !p.getWorld().getUID().equals(job.world)
                    || p.getInventory().getHeldItemSlot() != job.slot
                    || p.getInventory().getItemInMainHand().getType() != job.tool.getType()) {
                    jobs.remove(job.player, job); break;
                }
                Shape.Offset o = job.offsets.get(job.index++);
                Material expected = job.expected.get(o);
                if (expected == null) continue;
                World w = p.getWorld(); int x = job.x+o.x(), y = job.y+o.y(), z = job.z+o.z();
                if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                Block block = w.getBlockAt(x, y, z);
                if (block.getType() != expected || !w.getWorldBorder().isInside(block.getLocation()) || spawnProtected(p, block)) continue;
                ItemStack before = p.getInventory().getItemInMainHand().clone();
                internal.add(job.player);
                try { p.breakBlock(block); }
                finally { internal.remove(job.player); }
                ItemStack after = p.getInventory().getItemInMainHand();
                if (after.getType() != before.getType() || p.getInventory().getHeldItemSlot() != job.slot) {
                    tell(p, "stopped.tool");
                    jobs.remove(job.player, job); break;
                }
                job.tool = after.clone();
            }
            if (job.index >= job.offsets.size()) jobs.remove(job.player, job);
        }
    }

    /** Player.breakBlock skips vanilla spawn protection, which is checked before destroyBlock; same rule as vanilla. */
    private boolean spawnProtected(Player p, Block block) {
        int radius = getServer().getSpawnRadius();
        World main = getServer().getWorlds().get(0);
        if (radius <= 0 || p.isOp() || !block.getWorld().equals(main) || getServer().getOperators().isEmpty()) return false;
        Location spawn = main.getSpawnLocation();
        return Math.max(Math.abs(block.getX() - spawn.getBlockX()), Math.abs(block.getZ() - spawn.getBlockZ())) <= radius;
    }
    private Mode mode(Player p) { return modes.getOrDefault(p.getUniqueId(), Mode.OFF); }
    private Settings settings(Player p) {
        return preferences.computeIfAbsent(p.getUniqueId(), id -> {
            Settings result = new Settings();
            String key = id + ".";
            int pw = playerData.getInt(key + "plane-width", 3), ph = playerData.getInt(key + "plane-height", 3);
            if (Settings.validPlane(pw, ph)) { result.planeWidth = pw; result.planeHeight = ph; }
            int cw = playerData.getInt(key + "cube-width", 3), ch = playerData.getInt(key + "cube-height", 3), cd = playerData.getInt(key + "cube-depth", 3);
            if (Settings.validCube(cw, ch, cd)) { result.cubeWidth = cw; result.cubeHeight = ch; result.cubeDepth = cd; }
            int width = playerData.getInt(key + "stair-width", 2), height = playerData.getInt(key + "stair-height", 3), steps = playerData.getInt(key + "stair-steps", 4);
            if (Settings.validStairs(width, height, steps)) {
                result.stairWidth = width; result.stairHeight = height; result.stairSteps = steps;
            }
            int fw = playerData.getInt(key + "flatten-width", 5), fd = playerData.getInt(key + "flatten-depth", 5), fh = playerData.getInt(key + "flatten-height", 5);
            if (Settings.validFlatten(fw, fd, fh)) { result.flattenWidth = fw; result.flattenDepth = fd; result.flattenHeight = fh; }
            result.toolMask = Math.max(0, Math.min(127, playerData.getInt(key + "tools", Settings.DEFAULT_TOOLS)));
            result.highlight = playerData.getBoolean(key + "highlight", true);
            result.dropsAtFeet = playerData.getBoolean(key + "drops-at-feet", true);
            double durability = playerData.getDouble(key + "durability", 1);
            if (durability == 0 || durability == .5 || durability == 1) result.durability = durability;
            return result;
        });
    }
    private boolean changed(Player p) {
        Settings s = settings(p);
        jobs.remove(p.getUniqueId()); preview.clear(p.getUniqueId());
        String key = p.getUniqueId() + ".";
        playerData.set(key + "plane-width", s.planeWidth); playerData.set(key + "plane-height", s.planeHeight);
        playerData.set(key + "cube-width", s.cubeWidth); playerData.set(key + "cube-height", s.cubeHeight); playerData.set(key + "cube-depth", s.cubeDepth);
        playerData.set(key + "stair-width", s.stairWidth); playerData.set(key + "stair-height", s.stairHeight); playerData.set(key + "stair-steps", s.stairSteps);
        playerData.set(key + "flatten-width", s.flattenWidth); playerData.set(key + "flatten-depth", s.flattenDepth); playerData.set(key + "flatten-height", s.flattenHeight);
        playerData.set(key + "tools", s.toolMask);
        playerData.set(key + "highlight", s.highlight); playerData.set(key + "drops-at-feet", s.dropsAtFeet); playerData.set(key + "durability", s.durability);
        try {
            if (playersReadOnly) throw new IOException("players.yml could not be read or backed up");
            // Write a temporary file and swap it in, so a crash mid-write cannot corrupt everyone's settings.
            File temp = new File(playerFile.getParentFile(), playerFile.getName() + ".tmp");
            playerData.save(temp);
            Files.move(temp.toPath(), playerFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if (clientLink != null) clientLink.changed(p); return true;
        }
        catch (IOException e) { getLogger().severe("Cannot save player preferences: " + e.getMessage()); tell(p, "error.save"); return false; }
    }
    private boolean toolAllowed(Player p) {
        return (settings(p).toolMask & toolBit(p.getInventory().getItemInMainHand().getType())) != 0;
    }
    static int toolBit(Material material) {
        String name = material.name();
        if (material.isAir()) return 32;
        if (name.endsWith("_PICKAXE")) return 1;
        if (name.endsWith("_SHOVEL")) return 2;
        if (name.endsWith("_AXE")) return 4;
        if (name.endsWith("_HOE")) return 8;
        return material == Material.SHEARS ? 16 : 64;
    }
    Protocol.State snapshot(Player p) {
        return Protocol.State.of(settings(p));
    }
    boolean applySnapshot(Player p, Protocol.State state) {
        state.applyTo(settings(p)); return changed(p);
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void toolDamage(PlayerItemDamageEvent event) {
        Player p = event.getPlayer(); UUID id = p.getUniqueId();
        if (!internal.contains(id) && !originalBreaks.containsKey(id)) return;
        if (!event.getItem().equals(p.getInventory().getItemInMainHand())) return;
        event.setDamage(settings(p).scaledDamage(event.getDamage()));
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drops(BlockDropItemEvent event) {
        Player player = event.getPlayer(); UUID id = player.getUniqueId();
        boolean original = event.getBlock().equals(originalBreaks.get(id));
        if (!internal.contains(id) && !original) return;
        if (original) originalBreaks.remove(id);
        if (!settings(player).dropsAtFeet || event.getItems().isEmpty()) return;
        UUID world = event.getBlock().getWorld().getUID();
        List<Item> items = new ArrayList<>(event.getItems());
        // Items are spawned after the event. Move existing drops next tick; never create duplicates.
        getServer().getScheduler().runTask(this, () -> {
            if (event.isCancelled() || !player.isOnline() || !player.getWorld().getUID().equals(world)) return;
            Location feet = player.getLocation().add(0, .1, 0);
            for (Item item : items) {
                if (event.getItems().contains(item) && item.isValid() && item.getWorld().getUID().equals(world)) {
                    if (item.teleport(feet)) item.setVelocity(new Vector(0, 0, 0));
                }
            }
        });
    }
    private String toggleKeys(Messages m) {
        return m.get(getConfig().getBoolean("require-sneaking", true) ? "toggle.sneak" : "toggle.swap");
    }
    private void help(CommandSender sender) {
        Messages m = messages(sender);
        CommandText.help(toggleKeys(m), m, veinMining).forEach(sender::sendMessage);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {
        String sub = args.length == 0 ? "next" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("areaminer.admin")) { sender.sendMessage(messages(sender).get("error.permission")); return true; }
            reloadConfig(); readConfig(); jobs.clear(); preview.clear(); sender.sendMessage(messages(sender).get("reloaded")); return true;
        }
        if (sub.equals("help")) { help(sender); return true; }
        if (!(sender instanceof Player p)) { sender.sendMessage(messages(sender).get("error.in.game")); return true; }
        if (!p.hasPermission("areaminer.use")) { tell(p, "error.permission"); return true; }
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
                    tell(p, "status.options", s.highlight ? "ON" : "OFF", m.get(s.dropsAtFeet ? "drops.feet" : "drops.block"), s.durabilityLabel(m), s.toolMask);
                }
                case "highlight" -> {
                    require(args, 2); String value = args[1].toLowerCase(Locale.ROOT);
                    if (!value.equals("on") && !value.equals("off")) throw new IllegalArgumentException();
                    s.highlight = value.equals("on"); changed(p); tell(p, "set.highlight", s.highlight ? "ON" : "OFF");
                }
                case "drops" -> {
                    require(args, 2); String value = args[1].toLowerCase(Locale.ROOT);
                    if (!value.equals("feet") && !value.equals("block")) throw new IllegalArgumentException();
                    s.dropsAtFeet = value.equals("feet"); changed(p); tell(p, "set.drops", m.get(s.dropsAtFeet ? "drops.feet" : "drops.block"));
                }
                case "tools" -> {
                    // "all" = the five real tools, like the settings screen; hand/other items only via the number.
                    require(args,2); int mask = args[1].equalsIgnoreCase("all") ? s.toolMask | Settings.DEFAULT_TOOLS : Integer.parseInt(args[1]);
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
        return true;
    }
    private static void require(String[] args, int length) {
        if (args.length != length && !(length == 1 && args.length == 0)) throw new IllegalArgumentException();
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command c, String alias, String[] args) {
        return CommandText.suggestions(args, sender.hasPermission("areaminer.admin"));
    }
}

package areaminer.server;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
/** config/areaminer/config.json — same keys and defaults as the Paper config.yml. */
final class ServerConfig {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    static final class Data {
        @SerializedName("require-sneaking") boolean requireSneaking = true;
        @SerializedName("show-join-help") boolean showJoinHelp = true;
        @SerializedName("preview-enabled") boolean previewEnabled = true;
        @SerializedName("blocks-per-player-per-tick") int blocksPerPlayerPerTick = 64;
        @SerializedName("blocks-total-per-tick") int blocksTotalPerTick = 256;
        @SerializedName("vein-mining") boolean veinMining = true;
        @SerializedName("vein-max-blocks") int veinMaxBlocks = 64;
        List<String> materials = new ArrayList<>(List.of(
            "minecraft:stone", "minecraft:cobblestone", "minecraft:mossy_cobblestone",
            "minecraft:granite", "minecraft:polished_granite", "minecraft:diorite", "minecraft:polished_diorite",
            "minecraft:andesite", "minecraft:polished_andesite", "minecraft:deepslate", "minecraft:cobbled_deepslate",
            "minecraft:polished_deepslate", "minecraft:tuff", "minecraft:polished_tuff", "minecraft:calcite",
            "minecraft:smooth_stone", "minecraft:stone_bricks", "minecraft:mossy_stone_bricks",
            "minecraft:cracked_stone_bricks", "minecraft:chiseled_stone_bricks", "minecraft:deepslate_bricks",
            "minecraft:deepslate_tiles", "minecraft:netherrack", "minecraft:basalt", "minecraft:smooth_basalt",
            "minecraft:polished_basalt", "minecraft:blackstone", "minecraft:polished_blackstone", "minecraft:end_stone",
            "minecraft:sandstone", "minecraft:red_sandstone", "minecraft:dirt", "minecraft:coarse_dirt",
            "minecraft:rooted_dirt", "minecraft:grass_block", "minecraft:podzol", "minecraft:mycelium",
            "minecraft:dirt_path", "minecraft:farmland", "minecraft:mud", "minecraft:packed_mud", "minecraft:clay",
            "minecraft:sand", "minecraft:red_sand", "minecraft:gravel", "minecraft:soul_sand", "minecraft:soul_soil"));
        List<String> ores = new ArrayList<>(List.of(
            "minecraft:coal_ore", "minecraft:deepslate_coal_ore", "minecraft:iron_ore", "minecraft:deepslate_iron_ore",
            "minecraft:copper_ore", "minecraft:deepslate_copper_ore", "minecraft:gold_ore", "minecraft:deepslate_gold_ore",
            "minecraft:redstone_ore", "minecraft:deepslate_redstone_ore", "minecraft:emerald_ore", "minecraft:deepslate_emerald_ore",
            "minecraft:lapis_ore", "minecraft:deepslate_lapis_ore", "minecraft:diamond_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:nether_gold_ore", "minecraft:nether_quartz_ore", "minecraft:ancient_debris"));
    }
    final Data data;
    final Set<Block> materials = new HashSet<>(), ores = new HashSet<>();
    final int perPlayer, perTick, veinMax;
    final List<String> warnings = new ArrayList<>();
    private ServerConfig(Data data) {
        this.data = data;
        readBlocks(data.materials, materials, "material");
        readBlocks(data.ores, ores, "ore");
        veinMax = Math.max(1, Math.min(1000, data.veinMaxBlocks));
        perPlayer = Math.max(1, Math.min(1000, data.blocksPerPlayerPerTick));
        perTick = Math.max(1, Math.min(4000, data.blocksTotalPerTick));
    }
    private void readBlocks(List<String> names, Set<Block> into, String kind) {
        for (String name : names == null ? List.<String>of() : names) {
            Identifier id = Identifier.tryParse(name == null ? "" : name.toLowerCase(Locale.ROOT));
            Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isPresent() && !block.get().defaultBlockState().isAir() && block.get().defaultDestroyTime() >= 0) into.add(block.get());
            else warnings.add("Ignored invalid/unbreakable " + kind + ": " + name);
        }
    }
    static ServerConfig load(Path file) {
        Data data = null;
        try {
            if (Files.exists(file)) data = JSON.fromJson(Files.readString(file), Data.class);
        } catch (Exception e) {
            ServerConfig fallback = new ServerConfig(new Data());
            fallback.warnings.add("Cannot read " + file + ", using defaults: " + e.getMessage());
            return fallback;
        }
        if (data == null) {
            data = new Data();
            try { Files.createDirectories(file.getParent()); Files.writeString(file, JSON.toJson(data)); }
            catch (Exception ignored) { }
        }
        return new ServerConfig(data);
    }
}

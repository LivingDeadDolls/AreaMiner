package areaminer.client;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.network.chat.Component;
import areaminer.Settings;
import areaminer.protocol.Protocol;
public final class AreaMinerScreen extends Screen {
    private static final int TOP = 64, ROW = 24, GAP = 8, LABEL = 48;
    private static final String[] RANGE_ROWS = {"areaminer.row.plane", "areaminer.row.cube", "areaminer.row.stairs", "areaminer.row.flatten"};
    private final Screen parent;
    private Protocol.State draft = AreaMinerClient.config.state;
    private int page;
    private boolean dirty, applying;
    public AreaMinerScreen(Screen parent) { super(Component.translatable("areaminer.screen.title")); this.parent = parent; }
    public void received() {
        if (!dirty || applying) { draft = AreaMinerClient.config.state; dirty = false; applying = false; }
        rebuildWidgets();
    }
    private void set(String key, String value) {
        Map<String,String> data = draft.fields(); data.put(key,value); draft = Protocol.State.parse(data); dirty = true; rebuildWidgets();
    }
    /**
     * The drops button's note: the server decides (the button is disabled), or, away from an AreaMiner server, that a
     * server may decide for everyone. Connected to one that lets the player choose (singleplayer too): none.
     */
    public static String dropsNote() {
        if (AreaMinerClient.dropsLocked) return "areaminer.drops.locked";
        return AreaMinerClient.supported ? null : "areaminer.drops.not_connected";
    }
    private void toggleTool(int bit) { set("tools",""+(draft.tools() ^ bit)); }
    /** Steps a value through min..max, skipping values the protocol rejects (e.g. a 1×1 plane). */
    private void cycle(String key, int current, int min, int max) {
        for (int i = 1; i <= max - min + 1; i++) {
            int next = (current - min + i) % (max - min + 1) + min;
            try { set(key, "" + next); return; } catch (IllegalArgumentException ignored) { }
        }
    }
    private int total() { return Math.min(440, width - 24); }
    private int left() { return (width - total()) / 2; }
    /** Button in a three-column grid; indent leaves room on the left for the range page's row labels. */
    private Button button(Component label, int column, int row, int indent, Runnable action) {
        int itemWidth = (total() - indent - GAP * 2) / 3;
        return addRenderableWidget(Button.builder(label, b -> action.run())
            .bounds(left() + indent + column * (itemWidth + GAP), TOP + row * ROW, itemWidth, 20).build());
    }
    private Button button(Component label, int column, int row, Runnable action) { return button(label, column, row, 0, action); }
    private static Component t(String key, Object... args) { return Component.translatable(key, args); }
    private static Component onOff(boolean value) { return t(value ? "areaminer.on" : "areaminer.off"); }
    private Button range(String axis, int value, String meaningKey, int column, int row, Runnable action) {
        Button b = button(Component.literal(axis + ": " + value), column, row, LABEL, action);
        b.setTooltip(Tooltip.create(t(meaningKey)));
        return b;
    }
    private void keys() { minecraft.setScreen(new KeyBindsScreen(this, minecraft.options)); }
    @Override protected void init() {
        String[] pages = {"areaminer.tab.general", "areaminer.tab.range", "areaminer.tab.tools"};
        int tabWidth = (total() - 8) / 3;
        for (int i = 0; i < 3; i++) {
            final int index = i;
            Button tab = addRenderableWidget(Button.builder(t(pages[i]), b -> { page = index; rebuildWidgets(); })
                .bounds(left() + i * (tabWidth + 4), 34, tabWidth, 20).build());
            tab.active = page != i;
        }
        if (page == 0) {
            button(t("areaminer.highlight", onOff(draft.highlight())), 0, 0, () -> set("highlight", "" + !draft.highlight()));
            Button drops = button(t("areaminer.drops", t(draft.feet() ? "areaminer.drops.feet" : "areaminer.drops.block")), 1, 0, () -> set("feet", "" + !draft.feet()));
            drops.active = !AreaMinerClient.dropsLocked;
            String note = dropsNote();
            if (note != null) drops.setTooltip(Tooltip.create(t(note)));
            button(t("areaminer.durability", t(draft.durability() == 1 ? "areaminer.durability.normal" : draft.durability() == .5 ? "areaminer.durability.half" : "areaminer.durability.none")), 2, 0,
                () -> set("durability", draft.durability() == 1 ? "0.5" : draft.durability() == .5 ? "0" : "1"));
            button(t("areaminer.key.modifier", AreaMinerClient.modifierKey.getTranslatedKeyMessage()), 0, 1, this::keys);
            button(t("areaminer.key.cycle", AreaMinerClient.cycleKey.getTranslatedKeyMessage()), 1, 1, this::keys);
            button(t("areaminer.key.settings", AreaMinerClient.settingsKey.getTranslatedKeyMessage()), 2, 1, this::keys);
            button(t("areaminer.color", ColorScreen.name(draft.color())), 0, 2,
                () -> minecraft.setScreen(new ColorScreen(this, draft.color(), draft.gamingSpeed(), (rgb, speed) -> {
                    set("gamingspeed", "" + speed); set("color", "" + rgb);
                })));
            button(t("areaminer.reset"), 2, 2, () -> { draft = Protocol.State.defaults(); dirty = true; rebuildWidgets(); });
        } else if (page == 1) {
            int max = Settings.MAX_SIDE;
            range("X", draft.planeWidth(), "areaminer.axis.width", 0, 0, () -> cycle("planewidth", draft.planeWidth(), 1, max));
            range("Y", draft.planeHeight(), "areaminer.axis.height", 1, 0, () -> cycle("planeheight", draft.planeHeight(), 1, max));
            range("X", draft.cubeWidth(), "areaminer.axis.width", 0, 1, () -> cycle("cubewidth", draft.cubeWidth(), 1, max));
            range("Y", draft.cubeHeight(), "areaminer.axis.height", 1, 1, () -> cycle("cubeheight", draft.cubeHeight(), 1, max));
            range("Z", draft.cubeDepth(), "areaminer.axis.depth", 2, 1, () -> cycle("cubedepth", draft.cubeDepth(), 1, max));
            range("X", draft.stairWidth(), "areaminer.axis.stair_width", 0, 2, () -> cycle("stairwidth", draft.stairWidth(), 1, 10));
            range("Y", draft.stairHeight(), "areaminer.axis.stair_height", 1, 2, () -> cycle("stairheight", draft.stairHeight(), 2, 10));
            range("Z", draft.stairSteps(), "areaminer.axis.stair_steps", 2, 2, () -> cycle("stairsteps", draft.stairSteps(), 1, 10));
            range("X", draft.flattenWidth(), "areaminer.axis.flatten_width", 0, 3, () -> cycle("flattenwidth", draft.flattenWidth(), 1, max));
            range("Z", draft.flattenDepth(), "areaminer.axis.flatten_depth", 1, 3, () -> cycle("flattendepth", draft.flattenDepth(), 1, max));
            range("Y", draft.flattenHeight(), "areaminer.axis.flatten_height", 2, 3, () -> cycle("flattenheight", draft.flattenHeight(), 1, max));
        } else {
            String[] names = {"pickaxe", "shovel", "axe", "hoe", "shears", "hand", "other"};
            for (int i = 0; i < 7; i++) {
                final int bit = 1 << i;
                button(t("areaminer.tool." + names[i], onOff((draft.tools() & bit) != 0)), i % 3, i / 3, () -> toggleTool(bit));
            }
            // "All tools" covers the five real tools only; empty hand and other items stay separate toggles.
            int tools = Settings.DEFAULT_TOOLS;
            boolean allTools = (draft.tools() & tools) == tools;
            button(t("areaminer.tool.all", onOff(allTools)), 1, 2, () -> set("tools", "" + (allTools ? draft.tools() & ~tools : draft.tools() | tools)));
        }
        int half = (total() - GAP) / 2;
        addRenderableWidget(Button.builder(t("areaminer.save"), b -> {
            applying = true; dirty = false; AreaMinerClient.apply(draft); rebuildWidgets();
        }).bounds(left(), height - 28, half, 20).build());
        addRenderableWidget(Button.builder(t("areaminer.back"), b -> onClose()).bounds(left() + half + GAP, height - 28, half, 20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
        if (page == 1) {
            for (int row = 0; row < RANGE_ROWS.length; row++) {
                graphics.text(font, t(RANGE_ROWS[row]), left(), TOP + row * ROW + 6, 0xFFFFFFFF);
            }
        }
        // Wrapped to at most two lines above the footer; longer translations would otherwise be cut off.
        var lines = font.split(AreaMinerClient.status, width - 24);
        int shown = Math.min(2, lines.size());
        int color = AreaMinerClient.statusError ? 0xFFFF7777 : AreaMinerClient.supported ? 0xFF99FFAA : 0xFFFFDD88;
        for (int i = 0; i < shown; i++) graphics.text(font, lines.get(i), 12, height - 44 - (shown - 1 - i) * (font.lineHeight + 1), color);
    }
    @Override public void onClose() { minecraft.setScreen(parent); }
}

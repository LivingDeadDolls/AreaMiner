package areaminer.client;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import areaminer.Settings;
/**
 * Picks the highlight color: the named colors as swatches, red/green/blue sliders and a #RRGGBB field, with a
 * preview of the outline on stone. Done hands the color back to the settings screen, which saves it with the rest.
 */
public final class ColorScreen extends Screen {
    private static final int SWATCH = 20, GAP = 4, ROW = 24, PREVIEW = 64;
    private final Screen parent;
    /** Receives the picked color and gaming speed when Done is pressed. */
    public interface Done { void accept(int color, int gamingSpeed); }
    private final Done done;
    private int color, speed;
    /** The sliders' color: the last real color, also while the gaming highlight is picked. */
    private int base;
    private EditBox hex;
    private final Channel[] channels = new Channel[3];
    private Speed speedSlider;
    /** Set while the widgets are being updated to the color, so they do not feed it back. */
    private boolean syncing;
    public ColorScreen(Screen parent, int color, int gamingSpeed, Done done) {
        super(Component.translatable("areaminer.color.title"));
        this.parent = parent; this.color = color; this.speed = gamingSpeed; this.done = done;
        base = color == Settings.GAMING ? Settings.DEFAULT_COLOR : color;
    }
    /** The color's name (or #RRGGBB), drawn in that color; "Gaming" in the colors of the rainbow. */
    static Component name(int rgb) {
        if (rgb == Settings.GAMING) {
            String text = Component.translatable("areaminer.color.gaming").getString();
            MutableComponent rainbow = Component.empty();
            int[] letters = text.codePoints().toArray();
            for (int i = 0; i < letters.length; i++) {
                rainbow.append(Component.literal(Character.toString(letters[i])).withColor(Settings.hue(i * 300 / Math.max(1, letters.length))));
            }
            return rainbow;
        }
        String name = Settings.colorName(rgb);
        return (name == null ? Component.literal(Settings.hex(rgb)) : Component.translatable("areaminer.color." + name)).withColor(rgb);
    }
    public int color() { return color; }
    public int gamingSpeed() { return speed; }
    private int total() { return Math.min(320, width - 24); }
    private int left() { return (width - total()) / 2; }
    @Override protected void init() {
        List<Integer> swatches = new ArrayList<>(Settings.COLORS.values());
        swatches.add(Settings.GAMING);
        int count = swatches.size(), row = count * SWATCH + (count - 1) * GAP;
        int x = (width - row) / 2;
        for (int rgb : swatches) { addRenderableWidget(new Swatch(x, 40, rgb)); x += SWATCH + GAP; }
        int sliders = total() - PREVIEW - 12;
        String[] names = {"R", "G", "B"};
        for (int i = 0; i < 3; i++) channels[i] = addRenderableWidget(new Channel(left(), 72 + i * ROW, sliders, names[i], 16 - i * 8));
        hex = addRenderableWidget(new EditBox(font, left(), 72 + 3 * ROW + 2, 80, 18, Component.translatable("areaminer.color.hex")));
        hex.setMaxLength(7);
        hex.setHint(Component.literal("#RRGGBB"));
        hex.setValue(text(color));
        hex.setResponder(text -> {
            if (syncing) return;
            try { pick(Settings.parseColor(text), false); }
            catch (IllegalArgumentException incomplete) { }   // keep the color until the field holds a whole one
        });
        // Beside the color code: how fast the gaming highlight turns (only used for gaming).
        speedSlider = addRenderableWidget(new Speed(left() + 88, 72 + 3 * ROW, sliders - 88));
        int half = (total() - 8) / 2;
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
            done.accept(color, speed); minecraft.gui.setScreen(parent);
        }).bounds(left(), height - 28, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(left() + half + 8, height - 28, half, 20).build());
    }
    /** The text field: #RRGGBB, empty for the gaming highlight (its hint shows the format). */
    private static String text(int rgb) { return rgb == Settings.GAMING ? "" : Settings.hex(rgb); }
    /** Shows rgb on the sliders, and in the text field unless the change was typed there. */
    private void pick(int rgb, boolean updateText) {
        color = rgb;
        if (rgb != Settings.GAMING) base = rgb;
        syncing = true;
        for (Channel channel : channels) channel.show();
        speedSlider.active = rgb == Settings.GAMING;
        if (updateText) hex.setValue(text(rgb));
        syncing = false;
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(font, title, width / 2, 15, 0xFFFFFFFF);
        // A block in the stone's grey with a glowing outline, like the highlight on a wall.
        int x = left() + total() - PREVIEW, y = 72;
        graphics.fill(x, y, x + PREVIEW, y + PREVIEW, 0xFF7D7D7D);
        graphics.fill(x + 6, y + 6, x + PREVIEW - 6, y + PREVIEW - 6, 0xFF8F8F8F);
        if (color == Settings.GAMING) gamingOutline(graphics, x + 4, y + 4, PREVIEW - 8);
        else for (int i = 0; i < 2; i++) graphics.outline(x + 4 + i, y + 4 + i, PREVIEW - 8 - 2 * i, PREVIEW - 8 - 2 * i, 0xFF000000 | color);
        graphics.centeredText(font, name(color), x + PREVIEW / 2, y + PREVIEW + 6, 0xFFFFFFFF);
    }
    /** A 2-pixel square outline with the rainbow running round it, at the speed of the gaming highlight. */
    private void gamingOutline(GuiGraphicsExtractor graphics, int x, int y, int size) {
        long tick = Util.getMillis() / 50;
        int perimeter = 4 * (size - 2);
        for (int step = 0; step < perimeter; step += 2) {
            int side = step / (size - 2), along = step % (size - 2);
            int px = switch (side) { case 0 -> x + along; case 1 -> x + size - 2; case 2 -> x + size - 2 - along; default -> x; };
            int py = switch (side) { case 0 -> y; case 1 -> y + along; case 2 -> y + size - 2; default -> y + size - 2 - along; };
            int hue = (int) Math.floorMod(tick * 360 / Settings.gamingPeriod(speed) - step * 360L / perimeter, 360L);
            graphics.fill(px, py, px + 2, py + 2, 0xFF000000 | Settings.hue(hue));
        }
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }

    private final class Swatch extends AbstractWidget {
        private final int rgb;
        Swatch(int x, int y, int rgb) {
            super(x, y, SWATCH, SWATCH, name(rgb));
            this.rgb = rgb;
            setTooltip(Tooltip.create(name(rgb)));
        }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = rgb == color;
            int frame = selected ? 0xFFFFFFFF : isHoveredOrFocused() ? 0xFFA0A0A0 : 0xFF000000;
            graphics.fill(getX(), getY(), getRight(), getBottom(), frame);
            if (rgb == Settings.GAMING) {
                // Rainbow stripes across the swatch.
                for (int i = 0; i < SWATCH - 4; i++) {
                    graphics.fill(getX() + 2 + i, getY() + 2, getX() + 3 + i, getBottom() - 2, 0xFF000000 | Settings.hue(i * 300 / (SWATCH - 4)));
                }
            } else graphics.fill(getX() + 2, getY() + 2, getRight() - 2, getBottom() - 2, 0xFF000000 | rgb);
        }
        @Override public void onClick(MouseButtonEvent event, boolean doubleClick) { pick(rgb, true); }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    /** The gaming highlight's speed, 1 to MAX_GAMING_SPEED; a faster one set in the settings file shows its number until moved. */
    private final class Speed extends AbstractSliderButton {
        Speed(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), Math.min(1, (speed - 1) / (double) (Settings.MAX_GAMING_SPEED - 1)));
            active = color == Settings.GAMING;
            updateMessage();
        }
        @Override protected void updateMessage() { setMessage(Component.translatable("areaminer.color.speed", speed, Settings.gamingSeconds(speed))); }
        @Override protected void applyValue() { speed = 1 + (int) Math.round(value * (Settings.MAX_GAMING_SPEED - 1)); }
    }

    /** One of red, green and blue, 0-255; shift is its bit position in 0xRRGGBB. */
    private final class Channel extends AbstractSliderButton {
        private final String label;
        private final int shift;
        Channel(int x, int y, int width, String label, int shift) {
            super(x, y, width, 20, Component.empty(), 0);
            this.label = label; this.shift = shift;
            show();
        }
        private int level() { return (base >> shift) & 255; }
        void show() { value = level() / 255.0; updateMessage(); }
        @Override protected void updateMessage() { setMessage(Component.literal(label + ": " + level())); }
        @Override protected void applyValue() {
            if (syncing) return;
            int level = (int) Math.round(value * 255);
            base = (base & ~(255 << shift)) | level << shift;
            color = base;
            syncing = true;
            hex.setValue(Settings.hex(color));
            syncing = false;
        }
    }
}

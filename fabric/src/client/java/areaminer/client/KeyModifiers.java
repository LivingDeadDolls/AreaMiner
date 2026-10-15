package areaminer.client;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.InputWithModifiers;
/** Kept apart from AreaMinerClient (which reads the config path on load) so it can be unit-tested. */
final class KeyModifiers {
    private KeyModifiers() {}
    /**
     * Whether the toggle's modifier key (its own entry in Controls) is held. Shift, Ctrl and Alt count on
     * either side, using the event's modifier flags; any other key must be held down. Unbound = no modifier.
     */
    static boolean held(KeyMapping modifier, InputWithModifiers event) {
        if (modifier.isUnbound()) return true;
        InputConstants.Key key = InputConstants.getKey(modifier.saveString());
        if (key.getType() == InputConstants.Type.KEYBOARD) {
            int code = key.getValue();
            if (code == InputConstants.KEY_LSHIFT || code == InputConstants.KEY_RSHIFT) return event.hasShiftDown();
            if (code == InputConstants.KEY_LCONTROL || code == InputConstants.KEY_RCONTROL) return event.hasControlDown();
            if (code == InputConstants.KEY_LALT || code == InputConstants.KEY_RALT) return event.hasAltDown();
        }
        return modifier.isDown();
    }
}
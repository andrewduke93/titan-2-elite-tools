package com.titangamepad;

/**
 * Baked-in GBA layout, kept as one plain table so it can be remapped later
 * without touching the input or UHID code.
 *
 * Android KeyEvent codes are used as the table keys; the values are gamepad
 * bits / hat directions. No Titan Boy, no RetroArch, no Cannoli code here.
 */
public final class GamepadMapping {
    private GamepadMapping() {}

    // Button bits in the report, ordered by Cannoli's tester, NOT by
    // Android's generic order. Cannoli's tester counts C and Z as
    // buttons, shifting everything after B:
    //   tester: A, B, C, X, Y, Z, L, R, L2, R2, Select, Start, Mode
    //   bit:    0, 1, 2, 3, 4, 5, 6, 7,  8,  9,  10,     11,    12
    // Verified on-phone 2026-10-29: J->A, K->B, U->C, I->X, T->Y,
    // Y-key->Z, Space->L2, O->Select. So to make Cannoli see X, send
    // bit 3; to make it see Y, send bit 4; L is bit 6, R is bit 7,
    // Select is bit 10, Start is bit 11, Menu/Mode is bit 12.
    public static final int BTN_GBA_A      = 1 << 0;
    public static final int BTN_GBA_B      = 1 << 1;
    public static final int BTN_GBA_X      = 1 << 3;
    public static final int BTN_GBA_Y      = 1 << 4;
    public static final int BTN_L_SHOULDER = 1 << 6;
    public static final int BTN_R_SHOULDER = 1 << 7;
    public static final int BTN_L2         = 1 << 8;
    public static final int BTN_R2         = 1 << 9;
    public static final int BTN_SELECT     = 1 << 10;
    public static final int BTN_START      = 1 << 11;
    public static final int BTN_MODE       = 1 << 12;  // Cannoli Menu button
    public static final int BTN_L3         = 1 << 13;  // left stick click
    public static final int BTN_R3         = 1 << 14;  // right stick click

    // Hat (D-pad) values in the HID report.
    public static final int HAT_NEUTRAL = 8;

    /** Key code -> gamepad action. -1 means "not a gamepad key". */
    public static int buttonForKey(int keyCode) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_K: return BTN_GBA_A;       // GBA A
            case android.view.KeyEvent.KEYCODE_L: return BTN_GBA_B;       // GBA B
            case android.view.KeyEvent.KEYCODE_J: return BTN_GBA_X;       // GBA X
            case android.view.KeyEvent.KEYCODE_I: return BTN_GBA_Y;       // GBA Y
            case android.view.KeyEvent.KEYCODE_Q: return BTN_L_SHOULDER;  // L
            case android.view.KeyEvent.KEYCODE_E: return BTN_R_SHOULDER;  // R
            case android.view.KeyEvent.KEYCODE_C: return BTN_L2;          // L2
            case android.view.KeyEvent.KEYCODE_N: return BTN_R2;          // R2
            case android.view.KeyEvent.KEYCODE_T: return BTN_L3;          // L3
            case android.view.KeyEvent.KEYCODE_Y: return BTN_R3;          // R3
            case android.view.KeyEvent.KEYCODE_M: return BTN_MODE;        // Menu (Cannoli In-Game Menu / settings)
            case android.view.KeyEvent.KEYCODE_SPACE: return BTN_START;   // Start (swapped with Select)
            case android.view.KeyEvent.KEYCODE_ENTER: return BTN_SELECT;  // Select (swapped with Start)
            default: return -1;
        }
    }

    public static boolean isDpadKey(int keyCode) {
        switch (keyCode) {
            case android.view.KeyEvent.KEYCODE_W:
            case android.view.KeyEvent.KEYCODE_A:
            case android.view.KeyEvent.KEYCODE_S:
            case android.view.KeyEvent.KEYCODE_D:
                return true;
            default:
                return false;
        }
    }

    public static boolean isMappedKey(int keyCode) {
        return buttonForKey(keyCode) != -1 || isDpadKey(keyCode);
    }

    /** Mutable pressed-state, owned by the accessibility service. */
    public static final class State {
        public boolean up, down, left, right;
        public boolean rUp, rDown, rLeft, rRight;
        public int buttons;

        public void setDpad(int keyCode, boolean pressed) {
            switch (keyCode) {
                // Latest press wins over its opposite. This is the fix
                // for a stuck direction: if a key-up event is ever lost
                // (up stays true), pressing down must still go down
                // instead of cancelling to neutral. Diagonals still work,
                // because only the opposite direction is cleared, not
                // the perpendicular ones.
                //
                // Unstick rule (2026-10-29, Cannoli-menu stuck-down):
                // a fresh down (repeatCount 0, filtered by the caller)
                // for a direction that is ALREADY pressed means the
                // previous key-up was lost, so clear it instead of
                // re-setting it. A second tap of the stuck key frees it.
                // Normal play never sends a fresh down for a held key,
                // because held-key repeats arrive with repeatCount > 0
                // and are filtered out before this is called.
                case android.view.KeyEvent.KEYCODE_W:
                    if (pressed && up) up = false;
                    else { up = pressed; if (pressed) down = false; }
                    break;
                case android.view.KeyEvent.KEYCODE_S:
                    if (pressed && down) down = false;
                    else { down = pressed; if (pressed) up = false; }
                    break;
                case android.view.KeyEvent.KEYCODE_A:
                    if (pressed && left) left = false;
                    else { left = pressed; if (pressed) right = false; }
                    break;
                case android.view.KeyEvent.KEYCODE_D:
                    if (pressed && right) right = false;
                    else { right = pressed; if (pressed) left = false; }
                    break;
                default: break;
            }
        }

        public void setButton(int keyCode, boolean pressed) {
            int bit = buttonForKey(keyCode);
            if (bit == -1) return;
            // Same unstick rule as setDpad: a fresh down for a button
            // that is already down clears it (previous up was lost).
            if (pressed && (buttons & bit) != 0) buttons &= ~bit;
            else if (pressed) buttons |= bit; else buttons &= ~bit;
        }

        public void clear() {
            up = down = left = right = false;
            rUp = rDown = rLeft = rRight = false;
            buttons = 0;
        }

        public void clearRightStick() {
            rUp = rDown = rLeft = rRight = false;
        }

        /** Right-stick directions, by physical key position: I up, J left, K down, L right. */
        public void setRightStick(int keyCode, boolean pressed) {
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_I:
                    if (pressed && rUp) rUp = false;
                    else { rUp = pressed; if (pressed) rDown = false; }
                    break;
                case android.view.KeyEvent.KEYCODE_K:
                    if (pressed && rDown) rDown = false;
                    else { rDown = pressed; if (pressed) rUp = false; }
                    break;
                case android.view.KeyEvent.KEYCODE_J:
                    if (pressed && rLeft) rLeft = false;
                    else { rLeft = pressed; if (pressed) rRight = false; }
                    break;
                case android.view.KeyEvent.KEYCODE_L:
                    if (pressed && rRight) rRight = false;
                    else { rRight = pressed; if (pressed) rLeft = false; }
                    break;
                default: break;
            }
        }

        public static boolean isRightStickKey(int keyCode) {
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_I:
                case android.view.KeyEvent.KEYCODE_K:
                case android.view.KeyEvent.KEYCODE_J:
                case android.view.KeyEvent.KEYCODE_L:
                    return true;
                default: return false;
            }
        }

        /** Right-stick X axis (-127..127). */
        public int stickRX() {
            if (rLeft && !rRight) return -127;
            if (rRight && !rLeft) return 127;
            return 0;
        }

        /** Right-stick Y axis (-127..127, up negative). */
        public int stickRY() {
            if (rUp && !rDown) return -127;
            if (rDown && !rUp) return 127;
            return 0;
        }

        /** Hat value for the current D-pad state. Opposing directions cancel. */
        public int hat() {
            boolean u = up && !down;
            boolean d = down && !up;
            boolean l = left && !right;
            boolean r = right && !left;
            if (u && r) return 1;
            if (r && d) return 3;
            if (d && l) return 5;
            if (l && u) return 7;
            if (u) return 0;
            if (r) return 2;
            if (d) return 4;
            if (l) return 6;
            return HAT_NEUTRAL;
        }

        /** Left-stick X axis for the current WASD state (-127..127). */
        public int stickX() {
            if (left && !right) return -127;
            if (right && !left) return 127;
            return 0;
        }

        /** Left-stick Y axis for the current WASD state (-127..127, up negative). */
        public int stickY() {
            if (up && !down) return -127;
            if (down && !up) return 127;
            return 0;
        }
    }
}

package com.titangamepad;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * The driver itself, now with two profiles in one app.
 *
 * Mouse mode is a new profile, not a new app: the same Accessibility
 * service, the same Shizuku permission, the same launcher icon. The
 * launcher cycles Off -> Mouse -> Controller -> Off. Each profile
 * creates its own UHID device and destroys it on switch, so Android
 * only ever sees one input device from this app at a time:
 *   Controller: the UHID gamepad (existing behaviour, unchanged).
 *   Mouse:      a UHID mouse — Android shows the system cursor while
 *               the device exists and hides it when it is destroyed.
 *   Off:        no device, no keys consumed, keyboard types normally.
 *
 * Key filtering works the same in both profiles: a mapped key is only
 * consumed when that profile's device is actually running. If the
 * device cannot be created, onKeyEvent() returns false and the
 * keyboard behaves exactly as normal.
 *
 * Controller mapping (unchanged): WASD D-pad, KLJI face, Q/E shoulders,
 * C/N L2/R2, T/Y L3/R3, Enter Select, Space Start, M Menu, O/P stick
 * toggles, X mode query.
 * Mouse mapping: WASD move, I scroll up, J scroll down, K left click,
 * L right click, X mode query.
 */
public class GamepadAccessibilityService extends AccessibilityService {
    public static final String ACTION_TOGGLE = "com.titangamepad.TOGGLE";
    public static final String ACTION_SET_MODE = "com.titangamepad.SET_MODE";
    public static final String ACTION_MODE = "com.titangamepad.MODE";
    public static final String EXTRA_ENABLED = "enabled";
    public static final String EXTRA_STICK = "stick";
    public static final String EXTRA_MODE = "mode";

    public static final int MODE_OFF = 0;
    public static final int MODE_GAMEPAD = 1;
    public static final int MODE_MOUSE = 2;

    private static final String PREFS = "titangamepad";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MODE = "mode";
    private static final String KEY_STICK = "stick";
    private static final String KEY_RSTICK = "rstick";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final GamepadMapping.State state = new GamepadMapping.State();

    private volatile int mode = MODE_OFF;
    // Mouse-mode pause: true while an editable text field has focus.
    // While true, mouse keys pass through and type normally instead
    // of moving/clicking, and any held mouse movement/click is cleared.
    private volatile boolean inputFieldFocused;
    private volatile boolean stickMode;
    private volatile boolean rstickMode;
    private volatile GamepadSink gamepadSink;
    private volatile MouseSink mouseSink;
    private volatile String lastError;
    private volatile int startGen;

    // Mouse profile state, guarded by mouseLock.
    private final Object mouseLock = new Object();
    private boolean mUp, mDown, mLeft, mRight, mScrollUp, mScrollDown;
    private int mButtons;
    private int mTick;
    private volatile boolean mTickerRunning;
    private Thread mTicker;

    private interface GamepadSink {
        void send(int buttons, int hat, int stickX, int stickY, int stickRX, int stickRY) throws Exception;
        void close();
    }

    private interface MouseSink {
        void send(int buttons, int dx, int dy, int wheel) throws Exception;
        void close();
    }

    private final BroadcastReceiver toggleReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_SET_MODE.equals(intent.getAction())) {
                setMode(intent.getIntExtra(EXTRA_MODE, MODE_OFF));
            } else if (ACTION_TOGGLE.equals(intent.getAction())) {
                setMode(intent.getBooleanExtra(EXTRA_ENABLED, false) ? MODE_GAMEPAD : MODE_OFF);
            } else if (ACTION_MODE.equals(intent.getAction())) {
                setStickMode(intent.getBooleanExtra(EXTRA_STICK, false));
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        IntentFilter filter = new IntentFilter(ACTION_TOGGLE);
        filter.addAction(ACTION_SET_MODE);
        filter.addAction(ACTION_MODE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(toggleReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(toggleReceiver, filter);
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int savedMode = prefs.getInt(KEY_MODE, -1);
        if (savedMode == -1) {
            // Migrate the old on/off pref: on meant controller mode.
            savedMode = prefs.getBoolean(KEY_ENABLED, false) ? MODE_GAMEPAD : MODE_OFF;
            prefs.edit().putInt(KEY_MODE, savedMode).apply();
        }
        mode = savedMode;
        stickMode = false;
        rstickMode = false;
        prefs.edit().putBoolean(KEY_STICK, false).putBoolean(KEY_RSTICK, false).apply();
        if (mode != MODE_OFF) {
            startDriverAsync();
        }
        if (mode == MODE_MOUSE) refreshInputFocus();
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(toggleReceiver); } catch (Exception ignored) {}
        stopDriver();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_FOCUSED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            boolean editable = false;
            AccessibilityNodeInfo source = event.getSource();
            if (source != null) {
                try {
                    editable = isEditableNode(source);
                } finally {
                    try { source.recycle(); } catch (Exception ignored) {}
                }
            } else {
                CharSequence cls = event.getClassName();
                editable = cls != null
                        && (cls.toString().contains("EditText")
                        || cls.toString().contains("TextInput")
                        || cls.toString().contains("AutoComplete"));
            }
            // Text change/selection events only ever pause (they prove
            // the user is typing in a field); focus/click events can
            // also resume when the new focus is not editable.
            boolean wasPaused = inputFieldFocused;
            if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    || type == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                if (editable) inputFieldFocused = true;
            } else {
                inputFieldFocused = editable;
            }
            if (inputFieldFocused && !wasPaused && mode == MODE_MOUSE) {
                clearMouseState();
            }
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // New window (e.g. back from the launcher after enabling
            // mouse mode): re-check the currently focused input node
            // instead of blindly resuming, so a field that was already
            // focused before mouse mode was enabled still pauses.
            refreshInputFocus();
        }
    }

    private static boolean isEditableNode(AccessibilityNodeInfo node) {
        if (node.isEditable()) return true;
        try {
            if (node.getInputType() != 0) return true;
        } catch (Exception ignored) {}
        CharSequence cls = node.getClassName();
        return cls != null
                && (cls.toString().contains("EditText")
                || cls.toString().contains("TextInput")
                || cls.toString().contains("AutoComplete"));
    }

    private void clearMouseState() {
        synchronized (mouseLock) {
            mUp = mDown = mLeft = mRight = mScrollUp = mScrollDown = false;
            mButtons = 0;
            mTick = 0;
        }
        MouseSink ms = mouseSink;
        if (ms != null) {
            try { ms.send(0, 0, 0, 0); } catch (Exception ignored) {}
        }
    }

    private void refreshInputFocus() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            try {
                AccessibilityNodeInfo focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focus == null) {
                    inputFieldFocused = false;
                    return;
                }
                try {
                    boolean editable = isEditableNode(focus);
                    boolean wasPaused = inputFieldFocused;
                    inputFieldFocused = editable;
                    if (editable && !wasPaused && mode == MODE_MOUSE) {
                        clearMouseState();
                    }
                } finally {
                    try { focus.recycle(); } catch (Exception ignored) {}
                }
            } finally {
                try { root.recycle(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onInterrupt() {
        stopDriver();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (mode == MODE_OFF) return false;
        // Mouse mode pauses in input fields: keys type normally and
        // no mouse report is sent, until focus leaves the field.
        // Gamepad mode is unchanged by this (see note in README).
        if (mode == MODE_MOUSE && inputFieldFocused) return false;
        int keyCode = event.getKeyCode();
        // Escape hatch, always: Home, Back, and volume must pass
        // through, or there would be no way out of a mode without
        // reaching the launcher icon. Everything else is blocked
        // while a profile is on (see the mode handlers below).
        if (keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        // Mode query, both profiles: X is otherwise unmapped in mouse
        // mode, and was already the query key in controller mode.
        // BISECT-3: no blocking at all — query, then pass through.
        if (keyCode == KeyEvent.KEYCODE_X) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                toastShort("Mode: " + (mode == MODE_MOUSE ? "Mouse" : "Controller") + ".");
            }
            return false;
        }
        if (mode == MODE_MOUSE) return onMouseKeyEvent(event);
        return onGamepadKeyEvent(event);
    }

    // ---------------- Controller profile (unchanged behaviour) ----------------

    private boolean onGamepadKeyEvent(KeyEvent event) {
        GamepadSink s = gamepadSink;
        if (s == null) return false;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_O) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                setStickMode(!stickMode);
            }
            return false;  // BISECT-3: do not block, pass through
        }
        if (keyCode == KeyEvent.KEYCODE_P) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                setRstickMode(!rstickMode);
            }
            return false;  // BISECT-3: do not block, pass through
        }
        // BISECT-2: blocking stripped, menufix behaviour: unmapped
        // keys pass through and type, instead of being swallowed.
        if (!GamepadMapping.isMappedKey(keyCode)) return false;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return false;
        }
        boolean pressed = (action == KeyEvent.ACTION_DOWN);
        if (pressed && event.getRepeatCount() > 0) return false;  // BISECT-3

        synchronized (state) {
            if (rstickMode && GamepadMapping.State.isRightStickKey(keyCode)) {
                state.setRightStick(keyCode, pressed);
            } else if (GamepadMapping.isDpadKey(keyCode)) {
                state.setDpad(keyCode, pressed);
            } else {
                state.setButton(keyCode, pressed);
            }
            try {
                s.send(state.buttons, currentHat(), currentStickX(), currentStickY(), currentStickRX(), currentStickRY());
            } catch (Exception e) {
                main.post(new Runnable() {
                    @Override public void run() { stopDriver(); }
                });
                toast("Titan Gamepad stopped: " + e.getMessage());
                return false;
            }
        }
        // BISECT-3: send the gamepad report, but do NOT block the key —
        // pass it through, so DOWN/UP pairing cannot be broken by
        // consuming. Unmapped keys send no gamepad input (blank for
        // that key) and also pass through.
        return false;
    }

    // ---------------- Mouse profile ----------------

    private static boolean isMouseKey(int keyCode) {
        // Mouse mode, 2026-10-30 (corrected): WASD move, O = left
        // click, P = right click. No scroll keys.
        switch (keyCode) {
            case KeyEvent.KEYCODE_W:
            case KeyEvent.KEYCODE_A:
            case KeyEvent.KEYCODE_S:
            case KeyEvent.KEYCODE_D:
            case KeyEvent.KEYCODE_O:
            case KeyEvent.KEYCODE_P:
                return true;
            default:
                return false;
        }
    }

    private boolean onMouseKeyEvent(KeyEvent event) {
        MouseSink s = mouseSink;
        if (s == null) return false;
        int keyCode = event.getKeyCode();
        // BISECT-2: blocking stripped, menufix behaviour.
        if (!isMouseKey(keyCode)) return false;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return false;
        }
        boolean pressed = (action == KeyEvent.ACTION_DOWN);
        if (pressed && event.getRepeatCount() > 0) return false;  // BISECT-3, ticker handles holds
        synchronized (mouseLock) {
            // Unstick rule, same as the gamepad: a fresh down for a key
            // that is already down means the previous up was lost, so
            // clear it instead of re-setting it.
            switch (keyCode) {
                case KeyEvent.KEYCODE_W:
                    if (pressed && mUp) mUp = false; else { mUp = pressed; if (pressed) mDown = false; }
                    break;
                case KeyEvent.KEYCODE_S:
                    if (pressed && mDown) mDown = false; else { mDown = pressed; if (pressed) mUp = false; }
                    break;
                case KeyEvent.KEYCODE_A:
                    if (pressed && mLeft) mLeft = false; else { mLeft = pressed; if (pressed) mRight = false; }
                    break;
                case KeyEvent.KEYCODE_D:
                    if (pressed && mRight) mRight = false; else { mRight = pressed; if (pressed) mLeft = false; }
                    break;
                case KeyEvent.KEYCODE_O:
                    // Left click, bit 0.
                    if (pressed && (mButtons & 1) != 0) mButtons &= ~1;
                    else if (pressed) mButtons |= 1; else mButtons &= ~1;
                    break;
                case KeyEvent.KEYCODE_P:
                    // Right click, bit 1.
                    if (pressed && (mButtons & 2) != 0) mButtons &= ~2;
                    else if (pressed) mButtons |= 2; else mButtons &= ~2;
                    break;
                default: break;
            }
            // Clicks go out immediately; movement and scroll are
            // ticker-driven so a hold keeps moving/scrolling.
            if (keyCode == KeyEvent.KEYCODE_O || keyCode == KeyEvent.KEYCODE_P) {
                try {
                    s.send(mButtons, 0, 0, 0);
                } catch (Exception e) {
                    main.post(new Runnable() {
                        @Override public void run() { stopDriver(); }
                    });
                    toast("Titan Mouse stopped: " + e.getMessage());
                    return false;
                }
            }
        }
        // BISECT-3: mouse report sent (clicks immediate, movement via
        // ticker), key itself passes through, not blocked.
        return false;
    }

    private void startMouseTicker() {
        if (mTickerRunning) return;
        mTickerRunning = true;
        mTicker = new Thread(new Runnable() {
            @Override public void run() {
                while (mTickerRunning) {
                    try {
                        MouseSink s = mouseSink;
                        if (mode == MODE_MOUSE && s != null) {
                            boolean moving, scrolling;
                            int buttons, dx = 0, dy = 0, wheel = 0;
                            synchronized (mouseLock) {
                                moving = mUp || mDown || mLeft || mRight;
                                scrolling = mScrollUp || mScrollDown;
                                buttons = mButtons;
                                if (moving || scrolling) {
                                    mTick++;
                                    // Accelerating step: starts slow for
                                    // precise nudges, caps at 24/tick.
                                    int step = Math.min(4 + mTick / 8, 24);
                                    if (mLeft && !mRight) dx = -step;
                                    else if (mRight && !mLeft) dx = step;
                                    if (mUp && !mDown) dy = -step;
                                    else if (mDown && !mUp) dy = step;
                                    // Scroll every 5th tick (100 ms).
                                    if (scrolling && mTick % 5 == 0) {
                                        wheel = mScrollUp && !mScrollDown ? 3 : -3;
                                    }
                                } else {
                                    mTick = 0;
                                }
                            }
                            if (moving || scrolling) {
                                try {
                                    s.send(buttons, dx, dy, wheel);
                                } catch (Exception e) {
                                    main.post(new Runnable() {
                                        @Override public void run() { stopDriver(); }
                                    });
                                    toast("Titan Mouse stopped: " + e.getMessage());
                                    return;
                                }
                            }
                        }
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                        return;
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "titanmouse-ticker");
        mTicker.setDaemon(true);
        mTicker.start();
    }

    private void stopMouseTicker() {
        mTickerRunning = false;
        if (mTicker != null) mTicker.interrupt();
        mTicker = null;
        synchronized (mouseLock) {
            mUp = mDown = mLeft = mRight = mScrollUp = mScrollDown = false;
            mButtons = 0;
            mTick = 0;
        }
    }

    // ---------------- Mode switching / device creation ----------------

    private synchronized void setMode(int newMode) {
        if (newMode == mode && newMode != MODE_OFF) return;
        if (newMode == mode) return;
        mode = newMode;
        startGen++;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_MODE, newMode)
                .putBoolean(KEY_ENABLED, newMode != MODE_OFF).apply();
        stopDriver();
        if (newMode == MODE_OFF) {
            // No toast here: the launcher activity already announces
            // Off while foreground, same as the other modes.
        } else {
            stickMode = false;
            rstickMode = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_STICK, false).putBoolean(KEY_RSTICK, false).apply();
            startDriverAsync();
            if (newMode == MODE_MOUSE) refreshInputFocus();
        }
    }

    private void startDriverAsync() {
        final int gen = startGen;
        final int wantedMode = mode;
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                GamepadSink createdGamepad = null;
                MouseSink createdMouse = null;
                String createdVia = null;
                if (wantedMode == MODE_MOUSE) {
                    try {
                        createdMouse = startShizukuMouseHelper();
                        createdVia = "Shizuku helper";
                    } catch (Exception e) {
                        error = "Shizuku helper: " + e.getMessage();
                    }
                    if (createdMouse == null) {
                        try {
                            final UhidMouseDevice dev = UhidMouseDevice.open();
                            dev.sendReport(0, 0, 0, 0);
                            createdMouse = new MouseSink() {
                                @Override public void send(int b, int x, int y, int w) throws Exception { dev.sendReport(b, x, y, w); }
                                @Override public void close() { dev.close(); }
                            };
                            createdVia = "direct /dev/uhid";
                            error = null;
                        } catch (Exception e) {
                            error = (error == null ? "" : error + " | ") + "direct /dev/uhid: " + e.getMessage();
                        }
                    }
                } else {
                    try {
                        createdGamepad = startShizukuGamepadHelper();
                        createdVia = "Shizuku helper";
                    } catch (Exception e) {
                        error = "Shizuku helper: " + e.getMessage();
                    }
                    if (createdGamepad == null) {
                        try {
                            final UhidDevice dev = UhidDevice.open();
                            dev.sendReport(0, GamepadMapping.HAT_NEUTRAL);
                            createdGamepad = new GamepadSink() {
                                @Override public void send(int b, int h, int x, int y, int rx, int ry) throws Exception { dev.sendReport(b, h, x, y, rx, ry); }
                                @Override public void close() { dev.close(); }
                            };
                            createdVia = "direct /dev/uhid";
                            error = null;
                        } catch (Exception e) {
                            error = (error == null ? "" : error + " | ") + "direct /dev/uhid: " + e.getMessage();
                        }
                    }
                }
                if (gen != startGen || mode != wantedMode) {
                    if (createdGamepad != null) createdGamepad.close();
                    if (createdMouse != null) createdMouse.close();
                    return;
                }
                if (createdGamepad == null && createdMouse == null) {
                    lastError = error;
                    mode = MODE_OFF;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt(KEY_MODE, MODE_OFF).putBoolean(KEY_ENABLED, false).apply();
                    toast("Titan Gamepad could not start. " + error
                            + " Keys were NOT consumed; keyboard works normally.");
                    return;
                }
                if (wantedMode == MODE_MOUSE) {
                    mouseSink = createdMouse;
                    startMouseTicker();
                    // No success toast here: the launcher activity
                    // already announced the mode while foreground (see
                    // GamepadActivity). A toast posted from this
                    // background thread after device creation was
                    // being suppressed, which is why only the OFF
                    // toast ever showed.
                } else {
                    synchronized (state) { state.clear(); }
                    gamepadSink = createdGamepad;
                    try { createdGamepad.send(0, GamepadMapping.HAT_NEUTRAL, 0, 0, 0, 0); } catch (Exception ignored) {}
                }
            }
        }, "titangamepad-start").start();
    }

    private GamepadSink startShizukuGamepadHelper() throws Exception {
        final Process p = startShizukuProcess("com.titangamepad.UhidHelper");
        final OutputStream out = p.getOutputStream();
        return new GamepadSink() {
            @Override public synchronized void send(int b, int h, int x, int y, int rx, int ry) throws Exception {
                out.write(("STATE " + b + " " + h + " " + x + " " + y + " " + rx + " " + ry + "\n").getBytes("UTF-8"));
                out.flush();
            }
            @Override public void close() {
                try { out.write("QUIT\n".getBytes("UTF-8")); out.flush(); } catch (Exception ignored) {}
                try { p.destroy(); } catch (Exception ignored) {}
            }
        };
    }

    private MouseSink startShizukuMouseHelper() throws Exception {
        final Process p = startShizukuProcess("com.titangamepad.UhidMouseHelper");
        final OutputStream out = p.getOutputStream();
        return new MouseSink() {
            @Override public synchronized void send(int b, int x, int y, int w) throws Exception {
                out.write(("STATE " + b + " " + x + " " + y + " " + w + "\n").getBytes("UTF-8"));
                out.flush();
            }
            @Override public void close() {
                try { out.write("QUIT\n".getBytes("UTF-8")); out.flush(); } catch (Exception ignored) {}
                try { p.destroy(); } catch (Exception ignored) {}
            }
        };
    }

    private Process startShizukuProcess(String helperClass) throws Exception {
        if (!Shizuku.pingBinder()) {
            throw new Exception("Shizuku is not running");
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            throw new Exception("Shizuku permission not granted to Titan Gamepad "
                    + "(open the Shizuku app, grant it, then toggle again)");
        }
        String apk = getApplicationInfo().sourceDir;
        String[] cmd = new String[]{
            "/system/bin/app_process",
            "-Djava.class.path=" + apk,
            "/system/bin",
            helperClass,
        };
        Method m = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);
        final Process p = (Process) m.invoke(null, (Object) cmd, null, null);
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] firstLine = new String[1];
        Thread reader = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                    firstLine[0] = r.readLine();
                } catch (Exception ignored) {}
                latch.countDown();
            }
        }, "titangamepad-helper-wait");
        reader.setDaemon(true);
        reader.start();
        if (!latch.await(5, TimeUnit.SECONDS)) {
            try { p.destroy(); } catch (Exception ignored) {}
            throw new Exception("helper did not print READY in 5s (app_process path may not work on this ROM)");
        }
        if (firstLine[0] == null || !firstLine[0].equals("READY")) {
            try { p.destroy(); } catch (Exception ignored) {}
            throw new Exception("helper failed: " + firstLine[0]);
        }
        return p;
    }

    private synchronized void stopDriver() {
        stopMouseTicker();
        GamepadSink g = gamepadSink;
        gamepadSink = null;
        if (g != null) {
            try { g.send(0, GamepadMapping.HAT_NEUTRAL, 0, 0, 0, 0); } catch (Exception ignored) {}
            g.close();
        }
        MouseSink m = mouseSink;
        mouseSink = null;
        if (m != null) {
            try { m.send(0, 0, 0, 0); } catch (Exception ignored) {}
            m.close();
        }
        synchronized (state) { state.clear(); }
    }

    private int currentHat() {
        return stickMode ? GamepadMapping.HAT_NEUTRAL : state.hat();
    }

    private int currentStickX() {
        return stickMode ? state.stickX() : 0;
    }

    private int currentStickY() {
        return stickMode ? state.stickY() : 0;
    }

    private int currentStickRX() {
        return rstickMode ? state.stickRX() : 0;
    }

    private int currentStickRY() {
        return rstickMode ? state.stickRY() : 0;
    }

    private void setStickMode(boolean want) {
        stickMode = want;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_STICK, want).apply();
        synchronized (state) {
            state.up = state.down = state.left = state.right = false;
        }
        GamepadSink s = gamepadSink;
        if (mode == MODE_GAMEPAD && s != null) {
            try {
                synchronized (state) {
                    s.send(state.buttons, currentHat(), currentStickX(), currentStickY(), currentStickRX(), currentStickRY());
                }
            } catch (Exception ignored) {}
        }
        toastShort(want ? "WASD = Left Stick." : "WASD = D-pad.");
    }

    private void setRstickMode(boolean want) {
        rstickMode = want;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RSTICK, want).apply();
        synchronized (state) {
            state.clearRightStick();
            state.buttons &= ~(GamepadMapping.BTN_GBA_A | GamepadMapping.BTN_GBA_B
                    | GamepadMapping.BTN_GBA_X | GamepadMapping.BTN_GBA_Y);
        }
        GamepadSink s = gamepadSink;
        if (mode == MODE_GAMEPAD && s != null) {
            try {
                synchronized (state) {
                    s.send(state.buttons, currentHat(), currentStickX(), currentStickY(), currentStickRX(), currentStickRY());
                }
            } catch (Exception ignored) {}
        }
        toastShort(want ? "KLJI = Right Stick." : "KLJI = Face Buttons.");
    }

    private void toast(final String msg) {
        main.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(GamepadAccessibilityService.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    private Toast lastShortToast;

    private void toastShort(final String msg) {
        main.post(new Runnable() {
            @Override public void run() {
                if (lastShortToast != null) lastShortToast.cancel();
                final Toast t = Toast.makeText(GamepadAccessibilityService.this, msg, Toast.LENGTH_SHORT);
                lastShortToast = t;
                t.show();
                main.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (lastShortToast == t) {
                            t.cancel();
                            lastShortToast = null;
                        }
                    }
                }, 1000);
            }
        });
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabledPref(Context ctx) {
        return getModePref(ctx) != MODE_OFF;
    }

    public static int getModePref(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int savedMode = prefs.getInt(KEY_MODE, -1);
        if (savedMode == -1) {
            return prefs.getBoolean(KEY_ENABLED, false) ? MODE_GAMEPAD : MODE_OFF;
        }
        return savedMode;
    }
}

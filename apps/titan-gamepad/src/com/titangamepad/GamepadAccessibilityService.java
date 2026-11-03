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
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * The driver itself: controller only. Mouse mode was removed
 * 2026-11-03 at the user's request (unused) — the launcher now
 * cycles Off -> Controller -> Off, and there is no mouse device,
 * no mouse ticker, and no input-field pause logic left in here.
 *
 * Controller mapping: WASD D-pad, KLJI face, Q/E shoulders,
 * C/N L2/R2, T/Y L3/R3, Enter Select, Space Start, M Menu, O/P stick
 * toggles, X mode query.
 *
 * Key filtering: no key blocking anywhere — mapped keys send their
 * gamepad report and also pass through, unmapped keys pass through.
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

    private static final String PREFS = "titangamepad";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_MODE = "mode";
    private static final String KEY_STICK = "stick";
    private static final String KEY_RSTICK = "rstick";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final GamepadMapping.State state = new GamepadMapping.State();

    private volatile int mode = MODE_OFF;
    private volatile boolean stickMode;
    private volatile boolean rstickMode;
    private volatile GamepadSink gamepadSink;
    private volatile String lastError;
    private volatile int startGen;

    private interface GamepadSink {
        void send(int buttons, int hat, int stickX, int stickY, int stickRX, int stickRY) throws Exception;
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
        // Any saved mode that is no longer valid (e.g. the removed
        // mouse mode, value 2) falls back to Off rather than
        // resurrecting a device that no longer exists.
        if (savedMode != MODE_OFF && savedMode != MODE_GAMEPAD) {
            savedMode = MODE_OFF;
            prefs.edit().putInt(KEY_MODE, savedMode).apply();
        }
        mode = savedMode;
        stickMode = false;
        rstickMode = false;
        prefs.edit().putBoolean(KEY_STICK, false).putBoolean(KEY_RSTICK, false).apply();
        if (mode != MODE_OFF) {
            startDriverAsync();
        }
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(toggleReceiver); } catch (Exception ignored) {}
        stopDriver();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Controller mode needs no accessibility events: keys are
        // handled in onKeyEvent, and nothing pauses in text fields.
    }

    @Override
    public void onInterrupt() {
        stopDriver();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (mode == MODE_OFF) return false;
        int keyCode = event.getKeyCode();
        // Escape hatch, always: Home, Back, and volume must pass
        // through, or there would be no way out of a mode without
        // reaching the launcher icon.
        if (keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        // Mode query: X is otherwise unmapped in controller mode.
        // No blocking at all — query, then pass through.
        if (keyCode == KeyEvent.KEYCODE_X) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                toastShort("Mode: Controller.");
            }
            return false;
        }
        return onGamepadKeyEvent(event);
    }

    // ---------------- Controller profile ----------------

    private boolean onGamepadKeyEvent(KeyEvent event) {
        GamepadSink s = gamepadSink;
        if (s == null) return false;
        int keyCode = event.getKeyCode();
        if (keyCode == KeyEvent.KEYCODE_O) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                setStickMode(!stickMode);
            }
            return false;  // Do not block, pass through
        }
        if (keyCode == KeyEvent.KEYCODE_P) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                setRstickMode(!rstickMode);
            }
            return false;  // Do not block, pass through
        }
        if (!GamepadMapping.isMappedKey(keyCode)) return false;
        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return false;
        }
        boolean pressed = (action == KeyEvent.ACTION_DOWN);
        if (pressed && event.getRepeatCount() > 0) return false;

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
        // Send the gamepad report, but do NOT block the key —
        // pass it through, so DOWN/UP pairing cannot be broken by
        // consuming.
        return false;
    }

    // ---------------- Mode switching / device creation ----------------

    private synchronized void setMode(int newMode) {
        if (newMode == mode) return;
        mode = newMode;
        startGen++;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_MODE, newMode)
                .putBoolean(KEY_ENABLED, newMode != MODE_OFF).apply();
        stopDriver();
        if (newMode != MODE_OFF) {
            stickMode = false;
            rstickMode = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_STICK, false).putBoolean(KEY_RSTICK, false).apply();
            startDriverAsync();
        }
    }

    private void startDriverAsync() {
        final int gen = startGen;
        final int wantedMode = mode;
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                GamepadSink createdGamepad = null;
                String createdVia = null;
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
                if (gen != startGen || mode != wantedMode) {
                    if (createdGamepad != null) createdGamepad.close();
                    return;
                }
                if (createdGamepad == null) {
                    lastError = error;
                    mode = MODE_OFF;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putInt(KEY_MODE, MODE_OFF).putBoolean(KEY_ENABLED, false).apply();
                    toast("Titan Gamepad could not start. " + error
                            + " Keys were NOT consumed; keyboard works normally.");
                    return;
                }
                synchronized (state) { state.clear(); }
                gamepadSink = createdGamepad;
                try { createdGamepad.send(0, GamepadMapping.HAT_NEUTRAL, 0, 0, 0, 0); } catch (Exception ignored) {}
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
        GamepadSink g = gamepadSink;
        gamepadSink = null;
        if (g != null) {
            try { g.send(0, GamepadMapping.HAT_NEUTRAL, 0, 0, 0, 0); } catch (Exception ignored) {}
            g.close();
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
        if (savedMode != MODE_OFF && savedMode != MODE_GAMEPAD) {
            return MODE_OFF;
        }
        return savedMode;
    }
}

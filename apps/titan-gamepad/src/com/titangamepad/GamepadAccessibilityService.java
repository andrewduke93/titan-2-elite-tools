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
 * The driver itself.
 *
 * Device-independent input path, on purpose: it does NOT read Titan 2 raw
 * input devices (the part of TitanPad that the user reports does not work
 * on the Elite). It uses AccessibilityService key filtering, which works
 * on any Android physical keyboard: with FLAG_REQUEST_FILTER_KEY_EVENTS
 * and canRequestFilterKeyEvents, onKeyEvent() is called before the
 * foreground app, and returning true consumes the key.
 *
 * Suppression is therefore not a best-effort hope: a mapped key is only
 * consumed when the virtual gamepad is actually running (sink != null).
 * If the device cannot be created, onKeyEvent() returns false and the
 * keyboard behaves exactly as normal. The one case this code cannot
 * detect from inside the app is another accessibility service already
 * holding the single key-filter slot -- see the test steps.
 *
 * State lives in SharedPreferences so the toggle activity (a separate,
 * silent launcher) and this service agree, and so the driver restarts
 * if the service is rebound after a reboot, provided Shizuku is running.
 */
public class GamepadAccessibilityService extends AccessibilityService {
    public static final String ACTION_TOGGLE = "com.titangamepad.TOGGLE";
    public static final String ACTION_MODE = "com.titangamepad.MODE";
    public static final String EXTRA_ENABLED = "enabled";
    public static final String EXTRA_STICK = "stick";

    private static final String PREFS = "titangamepad";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_STICK = "stick";
    private static final String KEY_RSTICK = "rstick";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final GamepadMapping.State state = new GamepadMapping.State();

    private volatile boolean enabled;
    private volatile boolean stickMode;
    private volatile boolean rstickMode;
    private volatile Sink sink;
    private volatile String lastError;
    private volatile int startGen;

    private interface Sink {
        void send(int buttons, int hat, int stickX, int stickY, int stickRX, int stickRY) throws Exception;
        void close();
    }

    private final BroadcastReceiver toggleReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (ACTION_TOGGLE.equals(intent.getAction())) {
                boolean want = intent.getBooleanExtra(EXTRA_ENABLED, false);
                setEnabled(want);
            } else if (ACTION_MODE.equals(intent.getAction())) {
                setStickMode(intent.getBooleanExtra(EXTRA_STICK, false));
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Android 14+ (targetSdk 34) throws SecurityException if a
        // context-registered receiver is registered without an export
        // flag. This was the crash that made Android report the service
        // as "malfunctioning" on connect. This receiver is internal only,
        // so it is NOT_EXPORTED; the toggle activity sends to our own
        // package, which a NOT_EXPORTED receiver still accepts.
        IntentFilter filter = new IntentFilter(ACTION_TOGGLE);
        filter.addAction(ACTION_MODE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(toggleReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(toggleReceiver, filter);
        }
        enabled = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ENABLED, false);
        // Modes always start known: D-pad + face buttons. Stick modes
        // used to persist silently across toggles, so there was no way
        // to tell what mode the driver was in without remembering the
        // last toast. Resetting here (and on every driver ON) fixes that.
        stickMode = false;
        rstickMode = false;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_STICK, false).putBoolean(KEY_RSTICK, false).apply();
        if (enabled) {
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
        // No window-content work is needed; input comes via onKeyEvent().
    }

    @Override
    public void onInterrupt() {
        stopDriver();
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        Sink s = sink;
        if (!enabled || s == null) return false;  // pass through, never steal keys
        int keyCode = event.getKeyCode();
        // Mode query key: X is otherwise unmapped, so it costs no game
        // button. Press X while the driver is on to hear the current
        // mode in a toast, without changing anything. This is the fix
        // for "I can't tell what mode I'm in": the O/P toasts are
        // transient, and the mode used to persist silently.
        if (keyCode == android.view.KeyEvent.KEYCODE_X) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                toast("Mode: WASD = " + (stickMode ? "Left Stick" : "D-pad")
                        + ", KLJI = " + (rstickMode ? "Right Stick" : "Face Buttons") + ".");
            }
            return true;
        }
        // Mode toggle key, inside the driver itself (no second launcher):
        // press P while the driver is on to flip WASD between D-pad
        // and left stick. P is otherwise unmapped, so it costs no
        // game button. Toggle on press only, consume both down and up.
        if (keyCode == android.view.KeyEvent.KEYCODE_O) {
            int action = event.getAction();
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                setStickMode(!stickMode);
            }
            return true;
        }
        // Right-stick toggle key, same in-driver pattern: press P
        // while the driver is on to flip KLJI between face buttons
        // and the right stick. P is otherwise unmapped.
        if (keyCode == android.view.KeyEvent.KEYCODE_P) {
            int action = event.getAction();
            if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                setRstickMode(!rstickMode);
            }
            return true;
        }
        if (!GamepadMapping.isMappedKey(keyCode)) return false;

        int action = event.getAction();
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) {
            return false;
        }
        boolean pressed = (action == KeyEvent.ACTION_DOWN);
        if (pressed && event.getRepeatCount() > 0) return true;  // held key: no state change

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
                // Device died mid-game: stop cleanly and give the keyboard back.
                main.post(new Runnable() {
                    @Override public void run() { stopDriver(); }
                });
                toast("Titan Gamepad stopped: " + e.getMessage());
                return false;
            }
        }
        return true;  // consumed: the game must NOT also see the letter
    }

    private synchronized void setEnabled(boolean want) {
        if (want == enabled) return;  // onServiceConnected handles the initial start
        enabled = want;
        startGen++;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, want).apply();
        if (want) {
            // Known start mode, every time: D-pad + face buttons.
            stickMode = false;
            rstickMode = false;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_STICK, false).putBoolean(KEY_RSTICK, false).apply();
            startDriverAsync();
        } else {
            stopDriver();
            toast("Titan Gamepad OFF.");
        }
    }

    private void startDriverAsync() {
        final int gen = startGen;
        new Thread(new Runnable() {
            @Override public void run() {
                String error = null;
                Sink created = null;
                String createdVia = null;
                // Path 1: Shizuku first. Direct /dev/uhid from a normal
                // app is almost never openable, and Shizuku is the proven
                // path on this phone (Tablet Mode uses it). Trying it
                // first also makes the ON toast name the path that
                // actually matters for debugging.
                try {
                    created = startShizukuHelper();
                    createdVia = "Shizuku helper";
                } catch (Exception e) {
                    error = "Shizuku helper: " + e.getMessage();
                }
                // Path 2: direct, in-process UHID, if Shizuku failed.
                if (created == null) {
                    try {
                        final UhidDevice dev = UhidDevice.open();
                        dev.sendReport(0, GamepadMapping.HAT_NEUTRAL);
                        created = new Sink() {
                            @Override public void send(int b, int h, int x, int y, int rx, int ry) throws Exception { dev.sendReport(b, h, x, y, rx, ry); }
                            @Override public void close() { dev.close(); }
                        };
                        createdVia = "direct /dev/uhid";
                        error = null;
                    } catch (Exception e) {
                        error = (error == null ? "" : error + " | ") + "direct /dev/uhid: " + e.getMessage();
                    }
                }
                if (gen != startGen || !enabled) {
                    // Toggled off while starting: discard, do not resurrect.
                    if (created != null) created.close();
                    return;
                }
                if (created == null) {
                    lastError = error;
                    enabled = false;
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply();
                    toast("Titan Gamepad could not start. " + error
                            + " Keys were NOT consumed; keyboard works normally.");
                    return;
                }
                synchronized (state) { state.clear(); }
                sink = created;
                // Send a clean neutral report immediately, before any
                // key event. A menu (Cannoli's, especially) that latched
                // a previous direction must get a neutral report on
                // connect, not only on the next key press.
                try { created.send(0, GamepadMapping.HAT_NEUTRAL, 0, 0, 0, 0); } catch (Exception ignored) {}
                final String via = createdVia;
                toast("Titan Gamepad ON via " + via + " — WASD = D-pad, KLJI = Face Buttons.");
            }
        }, "titangamepad-start").start();
    }

    private Sink startShizukuHelper() throws Exception {
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
            "com.titangamepad.UhidHelper",
        };
        Method m = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);
        final Process p = (Process) m.invoke(null, (Object) cmd, null, null);
        // Wait for READY (or ERROR) on stdout, up to 5 seconds.
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
        final OutputStream out = p.getOutputStream();
        return new Sink() {
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

    private synchronized void stopDriver() {
        Sink s = sink;
        sink = null;
        if (s != null) {
            try { s.send(0, GamepadMapping.HAT_NEUTRAL, 0, 0, 0, 0); } catch (Exception ignored) {}
            s.close();
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
        // A mode flip must not carry a stuck direction with it: clear
        // the WASD state, so toggling O now un-sticks a latched down
        // instead of re-sending it as a stick axis or a hat.
        synchronized (state) {
            state.up = state.down = state.left = state.right = false;
        }
        Sink s = sink;
        if (enabled && s != null) {
            try {
                synchronized (state) {
                    s.send(state.buttons, currentHat(), currentStickX(), currentStickY(), currentStickRX(), currentStickRY());
                }
            } catch (Exception ignored) {}
        }
        toast(want ? "WASD = Left Stick." : "WASD = D-pad.");
    }

    private void setRstickMode(boolean want) {
        rstickMode = want;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RSTICK, want).apply();
        synchronized (state) {
            state.clearRightStick();
            // A held face button must not stick across the mode flip.
            state.buttons &= ~(GamepadMapping.BTN_GBA_A | GamepadMapping.BTN_GBA_B
                    | GamepadMapping.BTN_GBA_X | GamepadMapping.BTN_GBA_Y);
        }
        Sink s = sink;
        if (enabled && s != null) {
            try {
                synchronized (state) {
                    s.send(state.buttons, currentHat(), currentStickX(), currentStickY(), currentStickRX(), currentStickRY());
                }
            } catch (Exception ignored) {}
        }
        toast(want ? "KLJI = Right Stick." : "KLJI = Face Buttons.");
    }

    private void toast(final String msg) {
        main.post(new Runnable() {
            @Override public void run() {
                Toast.makeText(GamepadAccessibilityService.this, msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    public static boolean isAccessibilityEnabled(Context ctx) {
        String enabled = android.provider.Settings.Secure.getString(
                ctx.getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains("com.titangamepad/.GamepadAccessibilityService");
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabledPref(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }
}

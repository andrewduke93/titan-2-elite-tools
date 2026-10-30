package com.titangamepad;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/**
 * Toggle, with Shizuku first.
 *
 * Shizuku is the primary path for creating the virtual gamepad, not a
 * fallback, and it is handled the way Tablet Mode does it on this phone
 * (which is why Tablet Mode's prompt works): register the binder and
 * permission-result listeners, stay alive until the result arrives, and
 * only then flip the driver on. Earlier Titan Gamepad builds called
 * requestPermission() with no listener registered and finished
 * immediately, so no prompt ever reliably appeared.
 *
 * Turning OFF needs no Shizuku, so it flips straight away.
 * The accessibility-enabled gate was removed in an earlier fix: it
 * misread the service as disabled on this phone even with the toggle ON
 * and blocked everything after it.
 */
public class GamepadActivity extends Activity {
    private static final int REQ_SHIZUKU = 1001;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started = false;
    private boolean finished = false;

    private final Shizuku.OnBinderReceivedListener binderListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    beginIfNeeded();
                }
            };

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != REQ_SHIZUKU) return;
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        doToggle();
                    } else {
                        Toast.makeText(GamepadActivity.this,
                                "Shizuku permission denied — Titan Gamepad "
                                + "cannot create the virtual gamepad.",
                                Toast.LENGTH_LONG).show();
                        finishSilently();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean want = !GamepadAccessibilityService.isEnabledPref(this);
        if (!want) {
            // Turning off: no Shizuku needed.
            doToggle();
            return;
        }
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!started) {
                    Toast.makeText(GamepadActivity.this,
                            "Shizuku is not running — start Shizuku first, "
                            + "then launch Titan Gamepad again.",
                            Toast.LENGTH_LONG).show();
                    finishSilently();
                }
            }
        }, 10000);
        if (Shizuku.pingBinder()) {
            beginIfNeeded();
        }
        // Otherwise wait for onBinderReceived(); the timeout above covers
        // Shizuku never showing up.
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }

    private void beginIfNeeded() {
        if (started) return;
        started = true;
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            doToggle();
        } else {
            Shizuku.requestPermission(REQ_SHIZUKU);
        }
    }

    private void doToggle() {
        boolean want = !GamepadAccessibilityService.isEnabledPref(this);
        GamepadAccessibilityService.prefs(this).edit().putBoolean("enabled", want).apply();
        Intent intent = new Intent(GamepadAccessibilityService.ACTION_TOGGLE);
        intent.setPackage(getPackageName());
        intent.putExtra(GamepadAccessibilityService.EXTRA_ENABLED, want);
        sendBroadcast(intent);
        finishSilently();
    }

    private void finishSilently() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (finished) return;
                finished = true;
                finish();
            }
        });
    }
}

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
 * Toggles the driver: Off -> Controller -> Off, one launch per
 * step, with a toast naming the new mode. Mouse mode was removed
 * 2026-11-03, so there is no third step in the cycle anymore.
 *
 * Same Shizuku-first pattern as before (register the binder and
 * permission-result listeners, stay alive until the result arrives,
 * only then flip the mode). Turning OFF needs no Shizuku, so it flips
 * straight away. The mode is also written to prefs here, so if the
 * accessibility service is not yet connected it still starts in the
 * right mode when it connects.
 */
public class GamepadActivity extends Activity {
    private static final int REQ_SHIZUKU = 1001;

    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean started = false;
    private boolean finished = false;
    private int nextMode = GamepadAccessibilityService.MODE_GAMEPAD;
    // Static, so a new launch can cancel the previous launch's toast
    // instead of queuing behind it: rapid cycling used to stack
    // 3.5-second toasts, which is why they felt late and long.
    private static Toast sToast;

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
                        doSetMode();
                    } else {
                        Toast.makeText(GamepadActivity.this,
                                "Shizuku permission denied — Titan Gamepad "
                                + "cannot create the virtual device.",
                                Toast.LENGTH_LONG).show();
                        finishSilently();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int current = GamepadAccessibilityService.getModePref(this);
        if (current == GamepadAccessibilityService.MODE_OFF) {
            nextMode = GamepadAccessibilityService.MODE_GAMEPAD;
        } else {
            nextMode = GamepadAccessibilityService.MODE_OFF;
        }
        // Announce immediately, at launch, not after the Shizuku
        // handshake in doSetMode — that handshake was the other
        // source of lateness.
        showModeToast();
        if (nextMode == GamepadAccessibilityService.MODE_OFF) {
            doSetMode();
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
            doSetMode();
        } else {
            Shizuku.requestPermission(REQ_SHIZUKU);
        }
    }

    private void doSetMode() {
        GamepadAccessibilityService.prefs(this).edit()
                .putInt("mode", nextMode)
                .putBoolean("enabled", nextMode != GamepadAccessibilityService.MODE_OFF).apply();
        Intent intent = new Intent(GamepadAccessibilityService.ACTION_SET_MODE);
        intent.setPackage(getPackageName());
        intent.putExtra(GamepadAccessibilityService.EXTRA_MODE, nextMode);
        sendBroadcast(intent);
        finishSilently();
    }

    private void showModeToast() {
        // Short toast, cancelled after ~1s: Android's SHORT is still
        // 2s, which lingered. Cancelling early is the only way to
        // make it shorter, and the mode names are short enough to
        // read in a second.
        if (sToast != null) sToast.cancel();
        String modeName;
        if (nextMode == GamepadAccessibilityService.MODE_GAMEPAD) {
            modeName = "Controller mode.";
        } else {
            modeName = "Off.";
        }
        final Toast t = Toast.makeText(this, modeName, Toast.LENGTH_SHORT);
        sToast = t;
        t.show();
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (sToast == t) {
                    t.cancel();
                    sToast = null;
                }
            }
        }, 1000);
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

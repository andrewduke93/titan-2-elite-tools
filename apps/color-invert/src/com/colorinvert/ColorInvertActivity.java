package com.colorinvert;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Single-use, silent toggle for Android color inversion.
 * Uses the standard secure setting "accessibility_display_inversion_enabled"
 * (0 = off, 1 = on) via Shizuku — NOT the QS tile, which is not trusted
 * on this ROM. Reads the current value,
 * flips it, verifies, retries once if the set didn't stick.
 * No toast, no UI, no network.
 */
public class ColorInvertActivity extends Activity {
    private static final int REQ_SHIZUKU = 1001;
    private static final String KEY = "accessibility_display_inversion_enabled";
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
                        runToggle();
                    } else {
                        finishSilently();
                    }
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!started) finishSilently();
            }
        }, 6000);
        if (Shizuku.pingBinder()) beginIfNeeded();
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
            runToggle();
        } else {
            Shizuku.requestPermission(REQ_SHIZUKU);
        }
    }

    private void runToggle() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String current = runShizuku(new String[]{"settings", "get", "secure", KEY}).trim();
                    String target = "1".equals(current) ? "0" : "1";
                    runShizuku(new String[]{"settings", "put", "secure", KEY, target});
                    String after = runShizuku(new String[]{"settings", "get", "secure", KEY}).trim();
                    if (!target.equals(after)) {
                        runShizuku(new String[]{"settings", "put", "secure", KEY, target});
                    }
                } catch (Exception ignored) {
                }
                finishSilently();
            }
        }).start();
    }

    private String runShizuku(String[] cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);
        Process p = (Process) m.invoke(null, (Object) cmd, null, null);
        String out = readAll(p.getInputStream());
        readAll(p.getErrorStream());
        p.waitFor();
        return out;
    }

    private String readAll(java.io.InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
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

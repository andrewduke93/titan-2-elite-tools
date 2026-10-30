package com.grayscale;

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
 * Single-use, silent toggle for grayscale (monochrome) display.
 * Android implements grayscale as the daltonizer in monochromacy mode:
 * secure "accessibility_display_daltonizer" = 0 (monochromacy), enabled
 * by secure "accessibility_display_daltonizer_enabled" = 1.
 * On enable, the mode is forced to 0 so it is always grayscale, never a
 * color-blindness correction mode. On disable, only the enabled flag is
 * cleared, so the next enable returns to grayscale. Verifies and retries
 * once if the set didn't stick. No toast, no UI, no network.
 */
public class GrayscaleActivity extends Activity {
    private static final int REQ_SHIZUKU = 1001;
    private static final String ENABLED_KEY = "accessibility_display_daltonizer_enabled";
    private static final String MODE_KEY = "accessibility_display_daltonizer";
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
                    String current = runShizuku(new String[]{"settings", "get", "secure", ENABLED_KEY}).trim();
                    if ("1".equals(current)) {
                        runShizuku(new String[]{"settings", "put", "secure", ENABLED_KEY, "0"});
                    } else {
                        runShizuku(new String[]{"settings", "put", "secure", MODE_KEY, "0"});
                        runShizuku(new String[]{"settings", "put", "secure", ENABLED_KEY, "1"});
                    }
                    String after = runShizuku(new String[]{"settings", "get", "secure", ENABLED_KEY}).trim();
                    String want = "1".equals(current) ? "0" : "1";
                    if (!want.equals(after)) {
                        if ("1".equals(want)) {
                            runShizuku(new String[]{"settings", "put", "secure", MODE_KEY, "0"});
                        }
                        runShizuku(new String[]{"settings", "put", "secure", ENABLED_KEY, want});
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

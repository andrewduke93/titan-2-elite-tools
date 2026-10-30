package com.tabletmode;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * Single-use toggle, silent like Lock: launch -> flip DPI 210 (tablet)
 * <-> 289 (normal) -> finish. No toast, no UI, no network.
 * Runs "wm density" through Shizuku, exactly like the user's shell script.
 */
public class TabletModeActivity extends Activity {
    private static final int REQ_SHIZUKU = 1001;
    private static final int DPI_TABLET = 210;
    private static final int DPI_NORMAL = 289;

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
        // If Shizuku never shows up, don't hang invisibly forever.
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!started) {
                    finishSilently();
                }
            }
        }, 6000);
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
                    int current = parseOverride(runShizuku(new String[]{"wm", "density"}).out);
                    int target = (current == DPI_TABLET) ? DPI_NORMAL : DPI_TABLET;
                    runShizuku(new String[]{"wm", "density", String.valueOf(target)});
                    int after = parseOverride(runShizuku(new String[]{"wm", "density"}).out);
                    if (after != target) {
                        // One retry in case the first set didn't stick.
                        runShizuku(new String[]{"wm", "density", String.valueOf(target)});
                    }
                } catch (Exception ignored) {
                }
                finishSilently();
            }
        }).start();
    }

    /** Same fallback as the shell script: no override line -> assume normal (289). */
    private int parseOverride(String out) {
        if (out == null) return DPI_NORMAL;
        Matcher m = Pattern.compile("Override density:\\s*(\\d+)").matcher(out);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return DPI_NORMAL;
    }

    private static class CmdResult {
        String out = "";
        int code = -1;
    }

    private CmdResult runShizuku(String[] cmd) throws Exception {
        Method m = Shizuku.class.getDeclaredMethod("newProcess",
                String[].class, String[].class, String.class);
        m.setAccessible(true);
        Process p = (Process) m.invoke(null, (Object) cmd, null, null);
        CmdResult r = new CmdResult();
        r.out = readAll(p.getInputStream());
        readAll(p.getErrorStream());
        r.code = p.waitFor();
        return r;
    }

    private String readAll(java.io.InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line).append('\n');
        }
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

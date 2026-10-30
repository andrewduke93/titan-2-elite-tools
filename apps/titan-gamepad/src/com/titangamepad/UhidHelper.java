package com.titangamepad;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Entry point for the Shizuku fallback path.
 *
 * Run as the shell user (via Shizuku's app_process path, with this APK on
 * the classpath) when the app process itself cannot open /dev/uhid.
 * Protocol on stdin, one line at a time:
 *   STATE <buttons> <hat> <lx> <ly> <rx> <ry>  -- send one gamepad report
 *   (older shorter STATE lines are still accepted, axes default to 0)
 *   QUIT                    -- destroy the device and exit
 * EOF has the same effect as QUIT. Prints READY on stdout after the
 * device is created, or ERROR <message> and exits non-zero on failure.
 *
 * This class shares UhidDevice with the in-process path, so the HID
 * descriptor and report format are identical in both paths.
 */
public final class UhidHelper {
    private UhidHelper() {}

    public static void main(String[] args) {
        UhidDevice dev;
        try {
            dev = UhidDevice.open();
        } catch (Exception e) {
            System.out.println("ERROR " + e.getMessage());
            System.out.flush();
            System.exit(1);
            return;
        }
        System.out.println("READY");
        System.out.flush();
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.equals("QUIT")) break;
                if (line.startsWith("STATE ")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        try {
                            int buttons = Integer.parseInt(parts[1]);
                            int hat = Integer.parseInt(parts[2]);
                            int lx = parts.length >= 5 ? Integer.parseInt(parts[3]) : 0;
                            int ly = parts.length >= 5 ? Integer.parseInt(parts[4]) : 0;
                            int rx = parts.length >= 7 ? Integer.parseInt(parts[5]) : 0;
                            int ry = parts.length >= 7 ? Integer.parseInt(parts[6]) : 0;
                            dev.sendReport(buttons, hat, lx, ly, rx, ry);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            dev.close();
        }
    }
}

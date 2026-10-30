package com.titangamepad;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Entry point for the Shizuku path, same pattern as Titan Gamepad.
 * Run as the shell user (via Shizuku's app_process path, with this APK
 * on the classpath) when the app process itself cannot open /dev/uhid.
 * Protocol on stdin, one line at a time:
 *   STATE <buttons> <dx> <dy> <wheel>  -- send one mouse report
 *   QUIT                               -- destroy the device and exit
 * EOF has the same effect as QUIT. Prints READY on stdout after the
 * device is created, or ERROR <message> and exits non-zero on failure.
 */
public final class UhidMouseHelper {
    private UhidMouseHelper() {}

    public static void main(String[] args) {
        UhidMouseDevice dev;
        try {
            dev = UhidMouseDevice.open();
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
                    if (parts.length >= 5) {
                        try {
                            dev.sendReport(
                                    Integer.parseInt(parts[1]),
                                    Integer.parseInt(parts[2]),
                                    Integer.parseInt(parts[3]),
                                    Integer.parseInt(parts[4]));
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

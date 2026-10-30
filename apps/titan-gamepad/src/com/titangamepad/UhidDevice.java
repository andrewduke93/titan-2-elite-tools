package com.titangamepad;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Minimal UHID gamepad, written in plain Java so no native toolchain is needed.
 *
 * Constants are taken from the Linux kernel header include/uapi/linux/uhid.h
 * (verified in this build environment, not guessed):
 *   UHID_DESTROY = 1, UHID_CREATE2 = 11, UHID_INPUT2 = 12
 *   struct uhid_event is packed, 4 (type) + 4372 (create2 payload) = 4376 bytes.
 *   create2 layout: name[128] phys[64] uniq[64] rd_size(u16) bus(u16)
 *   vendor(u32) product(u32) version(u32) country(u32) rd_data[4096].
 *   input2 layout: size(u16) at offset 4, data at offset 6.
 *
 * One open of /dev/uhid = one device. Closing the fd destroys the device
 * automatically, so stop() is just destroy + close. A reader thread drains
 * kernel-to-userspace events (UHID_START / OPEN / CLOSE / OUTPUT); they are
 * ignored for an input-only gamepad, but draining them is required so the
 * kernel does not block on a full event queue.
 *
 * If /dev/uhid cannot be opened from this process (the usual Android case:
 * the node is not world-accessible), open() throws IOException with the
 * underlying reason, and the caller must try the Shizuku path instead of
 * pretending the device exists.
 */
public final class UhidDevice {
    public static final String UHID_PATH = "/dev/uhid";
    public static final int EVENT_SIZE = 4376;

    private static final int UHID_DESTROY = 1;
    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;

    private static final int BUS_USB = 0x03;

    /**
     * HID report descriptor: Generic Desktop / Gamepad.
     * Report (7 bytes): 15 buttons (1 bit each) + 1 padding bit,
     * hat switch (4 bits, 0..7 + 8 = neutral) + 4 padding bits,
     * X, Y, Z, Rz axes (signed 8-bit, centred at 0, unused here).
     * Button order follows Cannoli's tester, verified on-phone:
     * 1=A, 2=B, 3=C, 4=X, 5=Y, 6=Z, 7=L, 8=R, 9=L2, 10=R2,
     * 11=Select, 12=Start, 13=Mode (Cannoli's Menu button),
     * 14=L3, 15=R3.
     */
    public static final byte[] REPORT_DESCRIPTOR = new byte[]{
        (byte) 0x05, (byte) 0x01,       // Usage Page (Generic Desktop)
        (byte) 0x09, (byte) 0x05,       // Usage (Gamepad)
        (byte) 0xA1, (byte) 0x01,       // Collection (Application)
        (byte) 0x05, (byte) 0x09,       //   Usage Page (Button)
        (byte) 0x19, (byte) 0x01,       //   Usage Minimum (1)
        (byte) 0x29, (byte) 0x0F,       //   Usage Maximum (15)
        (byte) 0x15, (byte) 0x00,       //   Logical Minimum (0)
        (byte) 0x25, (byte) 0x01,       //   Logical Maximum (1)
        (byte) 0x75, (byte) 0x01,       //   Report Size (1)
        (byte) 0x95, (byte) 0x0F,       //   Report Count (15)
        (byte) 0x81, (byte) 0x02,       //   Input (Data, Variable, Absolute)
        (byte) 0x75, (byte) 0x01,       //   Report Size (1)
        (byte) 0x95, (byte) 0x01,       //   Report Count (1)
        (byte) 0x81, (byte) 0x03,       //   Input (Constant, Variable, Absolute)
        (byte) 0x05, (byte) 0x01,       //   Usage Page (Generic Desktop)
        (byte) 0x09, (byte) 0x39,       //   Usage (Hat Switch)
        (byte) 0x15, (byte) 0x00,       //   Logical Minimum (0)
        (byte) 0x25, (byte) 0x07,       //   Logical Maximum (7)
        (byte) 0x35, (byte) 0x00,       //   Physical Minimum (0)
        (byte) 0x46, (byte) 0x3B, (byte) 0x01, // Physical Maximum (315)
        (byte) 0x65, (byte) 0x14,       //   Unit (Degrees)
        (byte) 0x75, (byte) 0x04,       //   Report Size (4)
        (byte) 0x95, (byte) 0x01,       //   Report Count (1)
        (byte) 0x81, (byte) 0x42,       //   Input (Data, Variable, Absolute, Null)
        (byte) 0x75, (byte) 0x04,       //   Report Size (4)
        (byte) 0x95, (byte) 0x01,       //   Report Count (1)
        (byte) 0x81, (byte) 0x03,       //   Input (Constant, Variable, Absolute)
        (byte) 0x05, (byte) 0x01,       //   Usage Page (Generic Desktop)
        (byte) 0x09, (byte) 0x30,       //   Usage (X)
        (byte) 0x09, (byte) 0x31,       //   Usage (Y)
        (byte) 0x09, (byte) 0x32,       //   Usage (Z)
        (byte) 0x09, (byte) 0x35,       //   Usage (Rz)
        (byte) 0x15, (byte) 0x81,       //   Logical Minimum (-127)
        (byte) 0x25, (byte) 0x7F,       //   Logical Maximum (127)
        (byte) 0x75, (byte) 0x08,       //   Report Size (8)
        (byte) 0x95, (byte) 0x04,       //   Report Count (4)
        (byte) 0x81, (byte) 0x02,       //   Input (Data, Variable, Absolute)
        (byte) 0xC0,                    // End Collection
    };

    private final RandomAccessFile raf;
    private final java.io.OutputStream out;
    private volatile boolean closed;
    private Thread reader;

    private UhidDevice(RandomAccessFile raf) throws IOException {
        this.raf = raf;
        // Write through a stream on the same fd: RandomAccessFile.seek()
        // is not valid on a character device, so never seek before writing.
        this.out = new java.io.FileOutputStream(raf.getFD());
    }

    /** Open /dev/uhid and register the gamepad. Throws on any failure. */
    public static UhidDevice open() throws IOException {
        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(UHID_PATH, "rw");
        } catch (IOException e) {
            throw new IOException("cannot open " + UHID_PATH + ": " + e.getMessage(), e);
        }
        UhidDevice dev = new UhidDevice(raf);
        try {
            dev.writeCreate();
        } catch (IOException e) {
            try { raf.close(); } catch (IOException ignored) {}
            throw new IOException("UHID_CREATE2 failed: " + e.getMessage(), e);
        }
        dev.startReader();
        return dev;
    }

    private void writeCreate() throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(UHID_CREATE2);
        putString(buf, 4, "Titan Gamepad", 128);
        putString(buf, 132, "", 64);
        putString(buf, 196, "titangamepad", 64);
        buf.putShort(260, (short) REPORT_DESCRIPTOR.length);
        buf.putShort(262, (short) BUS_USB);
        buf.putInt(264, 0x1234);   // generic vendor, not a real product id
        buf.putInt(268, 0x5678);
        buf.putInt(272, 0);
        buf.putInt(276, 0);
        buf.position(280);
        buf.put(REPORT_DESCRIPTOR);
        writeFully(buf.array());
    }

    /** Send one gamepad report. buttons = HID button bits, hat = 0..8. */
    public synchronized void sendReport(int buttons, int hat) throws IOException {
        sendReport(buttons, hat, 0, 0);
    }

    /** Send one gamepad report with left-stick axes (X, Y in -127..127). */
    public synchronized void sendReport(int buttons, int hat, int stickX, int stickY) throws IOException {
        sendReport(buttons, hat, stickX, stickY, 0, 0);
    }

    /** Send one gamepad report with left- and right-stick axes. */
    public synchronized void sendReport(int buttons, int hat, int stickX, int stickY, int stickRX, int stickRY) throws IOException {
        byte[] report = new byte[]{
            (byte) (buttons & 0xFF),
            (byte) ((buttons >> 8) & 0xFF),
            (byte) (hat & 0x0F),
            (byte) stickX,
            (byte) stickY,
            (byte) stickRX,
            (byte) stickRY,
        };
        ByteBuffer buf = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(UHID_INPUT2);
        buf.putShort(4, (short) report.length);
        buf.position(6);
        buf.put(report);
        writeFully(buf.array());
    }

    private synchronized void writeFully(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    private void startReader() {
        reader = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[EVENT_SIZE];
                try {
                    while (!closed) {
                        int n = raf.read(buf);
                        if (n < 0) break;
                        // Kernel events (START/OPEN/CLOSE/OUTPUT/GET_REPORT)
                        // are intentionally ignored for an input-only pad.
                        // GET_REPORT is not answered: no feature/output
                        // report is defined by this descriptor.
                    }
                } catch (IOException ignored) {
                    // fd closed on stop(); that is the normal exit path.
                }
            }
        }, "uhid-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            ByteBuffer buf = ByteBuffer.allocate(EVENT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            buf.putInt(UHID_DESTROY);
            writeFully(buf.array());
        } catch (IOException ignored) {}
        try { raf.close(); } catch (IOException ignored) {}
    }

    private static void putString(ByteBuffer buf, int offset, String s, int len) {
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int n = Math.min(bytes.length, len - 1);
        buf.position(offset);
        buf.put(bytes, 0, n);
        Arrays.fill(buf.array(), offset + n, offset + len, (byte) 0);
    }
}

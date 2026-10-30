package com.titangamepad;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Minimal UHID mouse, written in plain Java so no native toolchain is needed.
 *
 * Same UHID framing as Titan Gamepad (uhid.h: UHID_DESTROY = 1,
 * UHID_CREATE2 = 11, UHID_INPUT2 = 12, struct uhid_event = 4376 bytes,
 * create2 layout: name[128] phys[64] uniq[64] rd_size(u16) bus(u16)
 * vendor(u32) product(u32) version(u32) country(u32) rd_data[4096],
 * input2 layout: size(u16) at offset 4, data at offset 6).
 *
 * HID report descriptor: Generic Desktop / Mouse, Pointer collection.
 * Report (4 bytes): 3 buttons (1 bit each) + 5 padding bits,
 * X, Y (signed 8-bit, relative), Wheel (signed 8-bit, relative).
 * Android treats a UHID mouse as a real mouse: the system cursor
 * appears while the device exists and disappears when it is destroyed,
 * which is the "virtual mouse shows when on" behaviour — no overlay
 * code is needed.
 *
 * One open of /dev/uhid = one device. Closing the fd destroys it.
 */
public final class UhidMouseDevice {
    public static final String UHID_PATH = "/dev/uhid";
    public static final int EVENT_SIZE = 4376;

    private static final int UHID_DESTROY = 1;
    private static final int UHID_CREATE2 = 11;
    private static final int UHID_INPUT2 = 12;

    private static final int BUS_USB = 0x03;

    public static final byte[] REPORT_DESCRIPTOR = new byte[]{
        (byte) 0x05, (byte) 0x01,       // Usage Page (Generic Desktop)
        (byte) 0x09, (byte) 0x02,       // Usage (Mouse)
        (byte) 0xA1, (byte) 0x01,       // Collection (Application)
        (byte) 0x09, (byte) 0x01,       //   Usage (Pointer)
        (byte) 0xA1, (byte) 0x00,       //   Collection (Physical)
        (byte) 0x05, (byte) 0x09,       //     Usage Page (Button)
        (byte) 0x19, (byte) 0x01,       //     Usage Minimum (1)
        (byte) 0x29, (byte) 0x03,       //     Usage Maximum (3)
        (byte) 0x15, (byte) 0x00,       //     Logical Minimum (0)
        (byte) 0x25, (byte) 0x01,       //     Logical Maximum (1)
        (byte) 0x75, (byte) 0x01,       //     Report Size (1)
        (byte) 0x95, (byte) 0x03,       //     Report Count (3)
        (byte) 0x81, (byte) 0x02,       //     Input (Data, Variable, Absolute)
        (byte) 0x75, (byte) 0x05,       //     Report Size (5)
        (byte) 0x95, (byte) 0x01,       //     Report Count (1)
        (byte) 0x81, (byte) 0x03,       //     Input (Constant, Variable, Absolute)
        (byte) 0x05, (byte) 0x01,       //     Usage Page (Generic Desktop)
        (byte) 0x09, (byte) 0x30,       //     Usage (X)
        (byte) 0x09, (byte) 0x31,       //     Usage (Y)
        (byte) 0x09, (byte) 0x38,       //     Usage (Wheel)
        (byte) 0x15, (byte) 0x81,       //     Logical Minimum (-127)
        (byte) 0x25, (byte) 0x7F,       //     Logical Maximum (127)
        (byte) 0x75, (byte) 0x08,       //     Report Size (8)
        (byte) 0x95, (byte) 0x03,       //     Report Count (3)
        (byte) 0x81, (byte) 0x06,       //     Input (Data, Variable, Relative)
        (byte) 0xC0,                    //   End Collection
        (byte) 0xC0,                    // End Collection
    };

    private final RandomAccessFile raf;
    private final java.io.OutputStream out;
    private volatile boolean closed;
    private Thread reader;

    private UhidMouseDevice(RandomAccessFile raf) throws IOException {
        this.raf = raf;
        this.out = new java.io.FileOutputStream(raf.getFD());
    }

    public static UhidMouseDevice open() throws IOException {
        RandomAccessFile raf;
        try {
            raf = new RandomAccessFile(UHID_PATH, "rw");
        } catch (IOException e) {
            throw new IOException("cannot open " + UHID_PATH + ": " + e.getMessage(), e);
        }
        UhidMouseDevice dev = new UhidMouseDevice(raf);
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
        putString(buf, 4, "Titan Mouse", 128);
        putString(buf, 132, "", 64);
        putString(buf, 196, "titanmouse", 64);
        buf.putShort(260, (short) REPORT_DESCRIPTOR.length);
        buf.putShort(262, (short) BUS_USB);
        buf.putInt(264, 0x1234);
        buf.putInt(268, 0x5679);
        buf.putInt(272, 0);
        buf.putInt(276, 0);
        buf.position(280);
        buf.put(REPORT_DESCRIPTOR);
        writeFully(buf.array());
    }

    /** Send one mouse report. buttons: bit0 = left, bit1 = right, bit2 = middle. */
    public synchronized void sendReport(int buttons, int dx, int dy, int wheel) throws IOException {
        byte[] report = new byte[]{
            (byte) (buttons & 0xFF),
            (byte) dx,
            (byte) dy,
            (byte) wheel,
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
                    }
                } catch (IOException ignored) {
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

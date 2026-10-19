package iq.haji.cashier.print;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** اتصال Bluetooth Classic بالطابعات التي تدعم SPP (الغالبية العظمى من الطابعات الحرارية المحمولة). */
@SuppressLint("MissingPermission") // الأذونات تُفحص يدوياً عبر BtPermissions قبل كل استدعاء
public class BluetoothTransport implements PrinterTransport {
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context ctx;
    private final String address;
    private final String name;
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;

    public BluetoothTransport(Context ctx, String address, String name) {
        this.ctx = ctx.getApplicationContext();
        this.address = address == null ? "" : address.trim();
        this.name = name == null ? "" : name;
    }

    @Override
    public void open() throws PrintException {
        if (!BtPermissions.hasConnect(ctx)) {
            throw new PrintException("إذن «الأجهزة القريبة» غير ممنوح. امنحه من شاشة إعدادات الطابعة.");
        }
        BluetoothManager bm = ctx.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null) throw new PrintException("هذا الجهاز لا يدعم البلوتوث");
        if (!adapter.isEnabled()) throw new PrintException("البلوتوث مغلق. فعّله ثم أعد المحاولة.");
        if (address.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(address)) {
            throw new PrintException("لم تُحدد طابعة بلوتوث. اختر طابعة من شاشة إعدادات الطابعة.");
        }

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
            if (BtPermissions.hasScan(ctx)) adapter.cancelDiscovery(); // البحث الجاري يبطئ الاتصال أو يفشله
        } catch (SecurityException | IllegalArgumentException e) {
            throw new PrintException("تعذر الوصول إلى الطابعة المحفوظة", e);
        }

        IOException first = null;
        try {
            connectWith(device, true);
            return;
        } catch (IOException e) {
            first = e;
            closeSocketQuietly();
        } catch (SecurityException e) {
            throw new PrintException("إذن البلوتوث مرفوض", e);
        }
        try {
            connectWith(device, false); // محاولة ثانية بقناة غير مؤمّنة، تحتاجها بعض الطابعات الرخيصة
        } catch (IOException e2) {
            closeSocketQuietly();
            throw new PrintException("تعذر الاتصال بالطابعة" + (name.isEmpty() ? "" : " «" + name + "»")
                    + ". تأكد أنها مفتوحة وقريبة، وأن جهازاً آخر غير متصل بها، وأنها مقترنة من إعدادات البلوتوث.",
                    first);
        } catch (SecurityException e) {
            throw new PrintException("إذن البلوتوث مرفوض", e);
        }
    }

    private void connectWith(BluetoothDevice device, boolean secure) throws IOException {
        BluetoothSocket s = secure ? device.createRfcommSocketToServiceRecord(SPP)
                : device.createInsecureRfcommSocketToServiceRecord(SPP);
        socket = s;
        s.connect();
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    private void closeSocketQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (out == null) throw new IOException("غير متصل");
        out.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        if (out != null) out.flush();
    }

    @Override
    public int readByte(int timeoutMs) throws IOException {
        if (in == null) throw new IOException("غير متصل");
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) return in.read();
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }

    @Override
    public void drain() throws IOException {
        if (in == null) return;
        while (in.available() > 0) //noinspection ResultOfMethodCallIgnored
            in.read();
    }

    @Override
    public boolean isOpen() {
        return socket != null && socket.isConnected();
    }

    @Override
    public void close() {
        closeSocketQuietly();
    }

    @Override
    public boolean isBluetooth() {
        return true;
    }

    @Override
    public String describe() {
        return "بلوتوث " + (name.isEmpty() ? address : name + " (" + address + ")");
    }
}

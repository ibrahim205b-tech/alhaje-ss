package iq.haji.cashier.print;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import iq.haji.cashier.data.AppSettings;

/**
 * مدير الاتصال بالطابعة: اكتشاف البلوتوث، الاتصال، الفصل، إعادة الاتصال، إرسال البيانات، حالة الورق.
 * كل العمليات المحجوبة (connect/send/query) يجب أن تعمل على executor() وليس على خيط الواجهة.
 */
@SuppressLint("MissingPermission") // الأذونات تُفحص يدوياً عبر BtPermissions
public final class PrinterManager {

    public static final String DELIVERY_NOTE =
            "ملاحظة: نجاح الإرسال يعني أن البيانات سُلّمت للطابعة فقط، ولا يضمن خروج الورق فعلياً "
                    + "(قد ينفد الورق أو يكون الغطاء مفتوحاً) لأن هذا الاتصال لا يرسل تأكيداً بالطباعة.";

    public enum State {DISCONNECTED, CONNECTING, CONNECTED, ERROR}

    public interface StateListener {
        void onStateChanged(State state, String message);
    }

    public interface DiscoveryListener {
        void onDeviceFound(DiscoveredPrinter printer);

        void onFinished();

        void onError(String message);
    }

    public interface Done {
        void onDone(boolean ok, String message);
    }

    private static PrinterManager instance;

    public static synchronized PrinterManager get(Context ctx) {
        if (instance == null) instance = new PrinterManager(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final AppSettings settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<StateListener> listeners = new CopyOnWriteArrayList<>();

    private PrinterTransport transport;
    private volatile State state = State.DISCONNECTED;
    private volatile String stateMessage = "غير متصلة";

    private BroadcastReceiver discoveryReceiver;
    private DiscoveryListener discoveryListener;

    private PrinterManager(Context app) {
        this.app = app;
        this.settings = new AppSettings(app);
    }

    public ExecutorService executor() {
        return executor;
    }

    // ---------------- الحالة ----------------

    public State getState() {
        return state;
    }

    public String getStateMessage() {
        return stateMessage;
    }

    public void addListener(StateListener l) {
        listeners.add(l);
    }

    public void removeListener(StateListener l) {
        listeners.remove(l);
    }

    private void setState(final State s, final String msg) {
        state = s;
        stateMessage = msg;
        main.post(() -> {
            for (StateListener l : listeners) l.onStateChanged(s, msg);
        });
    }

    // ---------------- الطابعة المحفوظة ----------------

    public boolean hasSelectedPrinter() {
        if ("NETWORK".equals(settings.connectionType())) {
            return !settings.get(AppSettings.PRINTER_IP, "").trim().isEmpty();
        }
        return !settings.get(AppSettings.PRINTER_BT_ADDRESS, "").trim().isEmpty();
    }

    public String describeSelected() {
        if ("NETWORK".equals(settings.connectionType())) {
            String ip = settings.get(AppSettings.PRINTER_IP, "").trim();
            return ip.isEmpty() ? "لم تُحدد طابعة" : "شبكة " + ip + ":" + settings.printerPort();
        }
        String addr = settings.get(AppSettings.PRINTER_BT_ADDRESS, "").trim();
        String name = settings.get(AppSettings.PRINTER_BT_NAME, "");
        if (addr.isEmpty()) return "لم تُحدد طابعة";
        return "بلوتوث " + (name.isEmpty() ? addr : name + " (" + addr + ")");
    }

    public void saveBluetoothPrinter(String name, String address) {
        settings.set(AppSettings.PRINTER_CONNECTION, "BLUETOOTH");
        settings.set(AppSettings.PRINTER_BT_NAME, name == null ? "" : name);
        settings.set(AppSettings.PRINTER_BT_ADDRESS, address == null ? "" : address);
    }

    public void saveNetworkPrinter(String ip, int port) {
        settings.set(AppSettings.PRINTER_CONNECTION, "NETWORK");
        settings.set(AppSettings.PRINTER_IP, ip == null ? "" : ip.trim());
        settings.setInt(AppSettings.PRINTER_PORT, port);
    }

    private PrinterTransport createTransport() throws PrintException {
        if ("NETWORK".equals(settings.connectionType())) {
            String ip = settings.get(AppSettings.PRINTER_IP, "").trim();
            if (ip.isEmpty()) throw new PrintException("أدخل عنوان IP للطابعة الشبكية من إعدادات الطابعة");
            return new NetworkTransport(ip, settings.printerPort());
        }
        String addr = settings.get(AppSettings.PRINTER_BT_ADDRESS, "").trim();
        if (addr.isEmpty()) {
            throw new PrintException("لم تُحدد طابعة. اختر طابعة من شاشة إعدادات الطابعة.");
        }
        return new BluetoothTransport(app, addr, settings.get(AppSettings.PRINTER_BT_NAME, ""));
    }

    // ---------------- الاتصال ----------------

    public synchronized void connect() throws PrintException {
        closeQuietly();
        setState(State.CONNECTING, "جارٍ الاتصال بالطابعة...");
        try {
            PrinterTransport t = createTransport();
            t.open();
            transport = t;
            setState(State.CONNECTED, "متصلة: " + t.describe());
        } catch (PrintException e) {
            transport = null;
            setState(State.ERROR, e.getMessage());
            throw e;
        }
    }

    public synchronized void disconnect() {
        closeQuietly();
        setState(State.DISCONNECTED, "غير متصلة");
    }

    private void closeQuietly() {
        if (transport != null) {
            transport.close();
            transport = null;
        }
    }

    public synchronized boolean isConnected() {
        return transport != null && transport.isOpen();
    }

    public void connectAsync(final Done d) {
        executor.execute(() -> {
            try {
                connect();
                post(d, true, "تم الاتصال بالطابعة");
            } catch (PrintException e) {
                post(d, false, e.getMessage());
            }
        });
    }

    public void disconnectAsync(final Done d) {
        executor.execute(() -> {
            disconnect();
            post(d, true, "تم فصل الاتصال");
        });
    }

    public void queryStatusAsync(final Done d) {
        executor.execute(() -> {
            try {
                String s = queryPaperStatus();
                if (s == null) {
                    post(d, true, "الاتصال سليم، لكن هذه الطابعة لا تُرجع حالة الورق، لذلك لا يمكن تأكيد وجود الورق.");
                } else {
                    post(d, true, "الاتصال سليم. " + s);
                }
            } catch (PrintException e) {
                post(d, false, e.getMessage());
            }
        });
    }

    private void post(final Done d, final boolean ok, final String msg) {
        if (d == null) return;
        main.post(() -> d.onDone(ok, msg));
    }

    // ---------------- الإرسال ----------------

    private static final int CHUNK = 1024;

    private void writeChunks(byte[] data, int[] written) throws IOException {
        long delay = transport.isBluetooth() ? 20 : 0;
        int off = 0;
        while (off < data.length) {
            int n = Math.min(CHUNK, data.length - off);
            transport.write(data, off, n);
            off += n;
            written[0] = off;
            if (delay > 0 && off < data.length) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("قُطع الإرسال");
                }
            }
        }
        transport.flush();
        drainUntil = System.currentTimeMillis() + Math.min(8000, 800 + data.length / 8);
    }

    /**
     * يرسل مهمة طباعة كاملة. إذا فشل الإرسال قبل كتابة أي بايت تُعاد المحاولة مرة واحدة بعد إعادة الاتصال.
     * إذا فشل بعد كتابة جزء لا تُعاد تلقائياً (منعاً لطباعة فاتورتين) وتُرمى رسالة توضح ذلك.
     */
    public synchronized void send(byte[] data) throws PrintException {
        // اتصال جديد لكل مهمة: بعد نفاد الورق أو إطفاء الطابعة يبقى الاتصال القديم «مفتوحاً» ظاهرياً
        // لكن ما يُكتب عليه يضيع بصمت، فيظهر «تم الإرسال» ولا يُطبع شيء.
        // لا نقطع الاتصال قبل أن تخرج بيانات المهمة السابقة من البلوتوث، وإلا تنقطع الفاتورة السابقة.
        long wait = drainUntil - System.currentTimeMillis();
        if (wait > 0) settle(wait);
        connect();
        settle(300);
        preflight();

        int[] written = {0};
        try {
            writeChunks(data, written);
            return;
        } catch (IOException first) {
            closeQuietly();
            if (written[0] > 0) {
                setState(State.ERROR, "انقطع الاتصال أثناء الإرسال");
                throw new PrintException("انقطع الاتصال أثناء إرسال الفاتورة (" + written[0] + " من "
                        + data.length + " بايت). قد تكون طُبعت جزئياً، تحقق من الورق قبل إعادة المحاولة.", first);
            }
        }

        // لم يُكتب شيء: الاتصال كان قديماً ومقطوعاً. إعادة اتصال ومحاولة واحدة آمنة.
        connect();
        written[0] = 0;
        try {
            writeChunks(data, written);
        } catch (IOException second) {
            closeQuietly();
            setState(State.ERROR, "فشل الإرسال");
            throw new PrintException(written[0] > 0
                    ? "انقطع الاتصال أثناء إرسال الفاتورة. قد تكون طُبعت جزئياً، تحقق من الورق قبل إعادة المحاولة."
                    : "تعذر الإرسال للطابعة. تأكد أنها تعمل وفيها ورق ثم أعد المحاولة.", second);
        }
    }

    /** الوقت التقريبي الذي تنتهي فيه البيانات المرسلة من الخروج عبر البلوتوث. */
    private long drainUntil;

    private static void settle(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * قبل الإرسال: يُخرج الطابعة من حالة الخطأ التي تبقى فيها بعد نفاد الورق ويمسح ما علق في ذاكرتها،
     * ثم يفحص الورق والغطاء. الطابعات التي لا ترد على الاستعلام تُرسل لها البيانات كالمعتاد.
     */
    private void preflight() throws PrintException {
        PrinterProtocol p = PrinterProtocols.current(settings);
        try {
            transport.drain();
            byte[] recover = p.recoverCommand();
            if (recover != null) {
                transport.write(recover, 0, recover.length);
                transport.flush();
                settle(150);
                transport.drain();
            }
            String problem = p.blockingProblem(queryStatus(p.coverStatusQuery()), queryStatus(p.paperStatusQuery()));
            if (problem != null) {
                setState(State.ERROR, problem);
                throw new PrintException(problem);
            }
        } catch (IOException e) {
            closeQuietly();
            setState(State.ERROR, "انقطع الاتصال");
            throw new PrintException("انقطع الاتصال بالطابعة. تأكد أنها مشغّلة ثم أعد المحاولة.", e);
        }
    }

    /** يرسل استعلام حالة ويعيد بايت الرد، أو -1 إذا لم ترد الطابعة. */
    private int queryStatus(byte[] q) throws IOException {
        if (q == null) return -1;
        transport.write(q, 0, q.length);
        transport.flush();
        return transport.readByte(800);
    }

    /** يعيد وصف حالة الورق أو null إذا لم تستجب الطابعة (لا تدعم الاستعلام). */
    public synchronized String queryPaperStatus() throws PrintException {
        if (transport == null || !transport.isOpen()) connect();
        PrinterProtocol p = PrinterProtocols.current(settings);
        byte[] q = p.paperStatusQuery();
        if (q == null) return null;
        try {
            transport.drain();
            transport.write(q, 0, q.length);
            transport.flush();
            int b = transport.readByte(1500);
            return b < 0 ? null : p.interpretPaperStatus(b);
        } catch (IOException e) {
            closeQuietly();
            setState(State.ERROR, "انقطع الاتصال");
            throw new PrintException("انقطع الاتصال بالطابعة", e);
        }
    }

    // ---------------- اكتشاف البلوتوث ----------------

    private BluetoothAdapter adapter() {
        BluetoothManager bm = app.getSystemService(BluetoothManager.class);
        return bm == null ? null : bm.getAdapter();
    }

    private static DiscoveredPrinter toPrinter(BluetoothDevice d) {
        boolean printer = false;
        BluetoothClass cls = d.getBluetoothClass();
        if (cls != null) printer = cls.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING;
        return new DiscoveredPrinter(d.getName(), d.getAddress(),
                d.getBondState() == BluetoothDevice.BOND_BONDED, printer);
    }

    /** الأجهزة المقترنة مسبقاً (تحتاج إذن الاتصال فقط). الطابعات تُرتَّب أولاً. */
    public List<DiscoveredPrinter> bondedDevices() {
        List<DiscoveredPrinter> out = new ArrayList<>();
        BluetoothAdapter a = adapter();
        if (a == null || !BtPermissions.hasConnect(app) || !a.isEnabled()) return out;
        try {
            Set<BluetoothDevice> set = a.getBondedDevices();
            if (set != null) for (BluetoothDevice d : set) out.add(toPrinter(d));
        } catch (SecurityException ignored) {
        }
        Collections.sort(out, (x, y) -> Boolean.compare(y.printerClass, x.printerClass));
        return out;
    }

    @SuppressWarnings("deprecation")
    private static BluetoothDevice deviceFrom(Intent i) {
        if (Build.VERSION.SDK_INT >= 33) {
            return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    /** يبدأ البحث عن أجهزة قريبة. يجب استدعاؤه من خيط الواجهة بعد منح الأذونات. */
    public void startDiscovery(final DiscoveryListener l) {
        stopDiscovery();
        if (!BtPermissions.hasAll(app)) {
            l.onError("إذن «الأجهزة القريبة» غير ممنوح");
            return;
        }
        BluetoothAdapter a = adapter();
        if (a == null) {
            l.onError("هذا الجهاز لا يدعم البلوتوث");
            return;
        }
        if (!a.isEnabled()) {
            l.onError("البلوتوث مغلق. فعّله ثم أعد البحث.");
            return;
        }
        discoveryListener = l;
        discoveryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String act = i.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(act)) {
                    BluetoothDevice d = deviceFrom(i);
                    DiscoveryListener dl = discoveryListener;
                    if (d != null && dl != null) dl.onDeviceFound(toPrinter(d));
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(act)) {
                    DiscoveryListener dl = discoveryListener;
                    stopDiscovery();
                    if (dl != null) dl.onFinished();
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        ContextCompat.registerReceiver(app, discoveryReceiver, f, ContextCompat.RECEIVER_EXPORTED);
        boolean started;
        try {
            started = a.startDiscovery();
        } catch (SecurityException e) {
            started = false;
        }
        if (!started) {
            stopDiscovery();
            l.onError("تعذر بدء البحث عن الأجهزة");
        }
    }

    public void stopDiscovery() {
        if (discoveryReceiver != null) {
            try {
                app.unregisterReceiver(discoveryReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            discoveryReceiver = null;
        }
        discoveryListener = null;
        BluetoothAdapter a = adapter();
        if (a != null && BtPermissions.hasScan(app)) {
            try {
                if (a.isDiscovering()) a.cancelDiscovery();
            } catch (SecurityException ignored) {
            }
        }
    }
}

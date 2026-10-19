package iq.haji.cashier;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.util.LinkedHashMap;
import java.util.Map;

import iq.haji.cashier.data.AppSettings;
import iq.haji.cashier.print.BtPermissions;
import iq.haji.cashier.print.DiscoveredPrinter;
import iq.haji.cashier.print.PrinterManager;
import iq.haji.cashier.print.ReceiptPrinter;
import iq.haji.cashier.print.ReceiptRenderer;
import iq.haji.cashier.ui.RowAdapter;
import iq.haji.cashier.util.Fmt;
import iq.haji.cashier.util.Ui;
import iq.haji.cashier.util.UiKit;

/**
 * إعدادات الطابعة: بحث واختيار طابعة بلوتوث، أو طابعة شبكية (IP/منفذ)، اتصال/فصل، فحص الحالة،
 * عرض الورق وحجم الخط والهوامش والمحاذاة والنسخ، واختبار طباعة. يطلب أذونات البلوتوث وقت الحاجة بعد شرحها.
 */
public class PrinterSettingsActivity extends PageActivity implements PrinterManager.StateListener {

    private PrinterManager pm;
    private AppSettings st;
    private ReceiptPrinter printer;
    private final Map<String, DiscoveredPrinter> devices = new LinkedHashMap<>();

    private LinearLayout deviceList, btPanel, netPanel;
    private TextView tvStatus, tvSelected, tvPermHint, btnScan, btnTest;
    private EditText etIp, etPort, etWidth, etMargin, etCopies;
    private final TextView[] connChips = new TextView[2];
    private final TextView[] paperChips = new TextView[2];
    private final TextView[] fontChips = new TextView[2];
    private final TextView[] alignChips = new TextView[3];
    private SwitchCompat swCut;
    private boolean scanning, busy;
    private Runnable afterPerm;

    private final ActivityResultLauncher<String[]> permLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), res -> onPermResult());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageTitle("إعدادات الطابعة");
        pm = PrinterManager.get(this);
        st = new AppSettings(this);
        printer = new ReceiptPrinter(this);

        LinearLayout h = UiKit.row(this);
        content.addView(h, new FrameLayout.LayoutParams(UiKit.MATCH, UiKit.MATCH));
        LinearLayout left = UiKit.column(this, h, 1, false);
        LinearLayout right = UiKit.column(this, h, 1, true);
        buildLeft(left);
        buildRight(right);
        loadValues();
        refreshStatus();
        refreshSelected();
        updatePanels();

        if (getIntent().getBooleanExtra("first_run", false)) {
            AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("تهيئة الطابعة")
                    .setMessage("1) شغّل الطابعة وفعّل البلوتوث في التابلت.\n"
                            + "2) اضغط «بحث عن أجهزة قريبة» (سيُطلب إذن «الأجهزة القريبة») ثم اختر طابعتك.\n"
                            + "3) اضغط «اتصال» ثم «اختبار الطباعة».\n\n"
                            + "إن كانت طابعتك شبكية اختر «شبكة» وأدخل عنوان IP والمنفذ (المعتاد 9100).")
                    .setPositiveButton("ابدأ", null)
                    .create();
            Ui.showRtl(d);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        pm.addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermHint();
        loadBonded();
        refreshStatus();
    }

    @Override
    protected void onStop() {
        pm.removeListener(this);
        pm.stopDiscovery();
        setScanning(false);
        super.onStop();
    }

    @Override
    public void onStateChanged(PrinterManager.State state, String message) {
        refreshStatus();
    }

    // ======================= بناء الواجهة =======================

    private void buildLeft(LinearLayout col) {
        LinearLayout conn = UiKit.card(this);
        conn.addView(UiKit.title(this, "نوع الاتصال"));
        LinearLayout chips = UiKit.chipRow(this, new String[]{"بلوتوث", "شبكة (Wi-Fi / Ethernet)"},
                connChips, 0, i -> {
                    st.set(AppSettings.PRINTER_CONNECTION, i == 0 ? "BLUETOOTH" : "NETWORK");
                    pm.disconnectAsync(null);
                    updatePanels();
                    refreshSelected();
                });
        conn.addView(chips, UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 10));

        // --- لوحة البلوتوث ---
        btPanel = UiKit.vertical(this);
        tvSelected = UiKit.text(this, "", 15, R.color.text_primary, true);
        btPanel.addView(tvSelected, UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        tvPermHint = UiKit.text(this, "⚠ إذن «الأجهزة القريبة» غير ممنوح بعد. سيُطلب عند البحث أو الاتصال.",
                13, R.color.warning, false);
        btPanel.addView(tvPermHint, UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 6));
        btnScan = UiKit.button(this, "بحث عن أجهزة قريبة", false);
        btnScan.setOnClickListener(v -> toggleScan());
        btPanel.addView(btnScan, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 10));
        deviceList = UiKit.vertical(this);
        btPanel.addView(deviceList, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
        btPanel.addView(UiKit.hint(this, "إن لم تظهر الطابعة فاقرنها أولاً من إعدادات بلوتوث الجهاز (الطابعات تحتاج "
                + "غالباً رمز 0000 أو 1234) ثم ارجع واختَرها من القائمة. عند اختيار الطابعة تُحفظ للاستعمال لاحقاً."));
        conn.addView(btPanel);

        // --- لوحة الشبكة ---
        netPanel = UiKit.vertical(this);
        netPanel.addView(UiKit.label(this, "عنوان IP للطابعة"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        etIp = Ui.editText(this, "مثال: 192.168.1.50", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, "");
        netPanel.addView(etIp, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 4));
        netPanel.addView(UiKit.label(this, "المنفذ (المعتاد 9100)"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 10));
        etPort = Ui.editText(this, "9100", InputType.TYPE_CLASS_NUMBER, "");
        netPanel.addView(etPort, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 4));
        TextView saveNet = UiKit.button(this, "حفظ عنوان الشبكة", false);
        saveNet.setOnClickListener(v -> saveNetwork());
        netPanel.addView(saveNet, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 10));
        netPanel.addView(UiKit.hint(this, "يفترض التطبيق بروتوكول TCP الخام (RAW) مع أوامر ESC/POS. "
                + "إن كانت طابعتك تتطلب بروتوكولاً آخر فهي تحتاج إضافة بروتوكول جديد (راجع PrinterProtocols)."));
        conn.addView(netPanel);
        col.addView(conn, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 0));

        // --- الحالة والاختبار ---
        LinearLayout stat = UiKit.card(this);
        stat.addView(UiKit.title(this, "حالة الاتصال"));
        tvStatus = UiKit.text(this, "", 15, R.color.text_secondary, true);
        stat.addView(tvStatus, UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 8));
        LinearLayout r1 = UiKit.row(this);
        TextView bc = UiKit.button(this, "اتصال", true);
        bc.setOnClickListener(v -> doConnect());
        TextView bd = UiKit.button(this, "فصل الاتصال", false);
        bd.setOnClickListener(v -> doDisconnect());
        r1.addView(bc, new LinearLayout.LayoutParams(0, UiKit.WRAP, 1f));
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, UiKit.WRAP, 1f);
        p2.setMarginStart(Ui.dp(this, 8));
        r1.addView(bd, p2);
        stat.addView(r1, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12));
        TextView bs = UiKit.button(this, "فحص الحالة / الورق", false);
        bs.setOnClickListener(v -> doQueryStatus());
        stat.addView(bs, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
        btnTest = UiKit.button(this, "اختبار الطباعة", true);
        btnTest.setOnClickListener(v -> doTestPrint());
        stat.addView(btnTest, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
        stat.addView(UiKit.hint(this, PrinterManager.DELIVERY_NOTE), UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
        col.addView(stat, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12));
    }

    private void buildRight(LinearLayout col) {
        LinearLayout c = UiKit.card(this);
        c.addView(UiKit.title(this, "الورق والطباعة"));

        c.addView(UiKit.label(this, "عرض الورق"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        c.addView(UiKit.chipRow(this, new String[]{"58 مم", "80 مم"}, paperChips, 1, i -> {
            st.setInt(AppSettings.PAPER_WIDTH_MM, i == 0 ? 58 : 80);
        }), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 4));

        c.addView(UiKit.label(this, "عرض الطباعة بالنقاط (اختياري)"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        etWidth = Ui.editText(this, "الافتراضي: 384 لـ 58مم و576 لـ 80مم", InputType.TYPE_CLASS_NUMBER, "");
        c.addView(etWidth, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 4));
        c.addView(UiKit.hint(this, "إن قُطع طرف الفاتورة على ورق 80مم جرّب 512."));

        c.addView(UiKit.label(this, "حجم الخط"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        c.addView(UiKit.chipRow(this, new String[]{"عادي", "كبير"}, fontChips, 0, i -> {
            st.setInt(AppSettings.PRINT_FONT_SCALE, i == 0 ? 1 : 2);
        }), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 4));

        c.addView(UiKit.label(this, "محاذاة النصوص الحرة (العنوان، الملاحظات)"),
                UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        c.addView(UiKit.chipRow(this, new String[]{"يمين", "وسط", "يسار"}, alignChips, 0, i -> {
            st.set(AppSettings.PRINT_ALIGN, i == 0 ? "RIGHT" : (i == 1 ? "CENTER" : "LEFT"));
        }), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 4));

        c.addView(UiKit.label(this, "الهامش بالنقاط (0 إلى 60)"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        etMargin = Ui.editText(this, "8", InputType.TYPE_CLASS_NUMBER, "");
        c.addView(etMargin, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 4));

        c.addView(UiKit.label(this, "عدد النسخ (1 إلى 5)"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        etCopies = Ui.editText(this, "1", InputType.TYPE_CLASS_NUMBER, "");
        c.addView(etCopies, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 4));

        swCut = UiKit.toggle(this, "قص الورق بعد كل فاتورة (للطابعات ذات القاطع)", true);
        c.addView(swCut, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));

        TextView save = UiKit.button(this, "حفظ إعدادات الطباعة", true);
        save.setOnClickListener(v -> {
            if (savePrintSettings()) Ui.toast(this, "تم حفظ إعدادات الطباعة");
        });
        c.addView(save, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 14));
        col.addView(c, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 0));
    }

    private void loadValues() {
        UiKit.select(connChips, isNetwork() ? 1 : 0);
        UiKit.select(paperChips, st.paperWidthMm() == 58 ? 0 : 1);
        UiKit.select(fontChips, st.getInt(AppSettings.PRINT_FONT_SCALE, 1) == 2 ? 1 : 0);
        String a = st.get(AppSettings.PRINT_ALIGN, "RIGHT");
        UiKit.select(alignChips, "CENTER".equals(a) ? 1 : ("LEFT".equals(a) ? 2 : 0));
        etIp.setText(st.get(AppSettings.PRINTER_IP, ""));
        etPort.setText(String.valueOf(st.printerPort()));
        int dots = st.getInt(ReceiptRenderer.KEY_WIDTH_DOTS, 0);
        etWidth.setText(dots > 0 ? String.valueOf(dots) : "");
        etMargin.setText(String.valueOf(st.getInt(AppSettings.PRINT_MARGIN_DOTS, 8)));
        etCopies.setText(String.valueOf(st.copies()));
        swCut.setChecked(st.getBool(AppSettings.PRINT_CUT, true));
    }

    private boolean isNetwork() {
        return "NETWORK".equals(st.connectionType());
    }

    private void updatePanels() {
        boolean net = isNetwork();
        UiKit.select(connChips, net ? 1 : 0);
        netPanel.setVisibility(net ? View.VISIBLE : View.GONE);
        btPanel.setVisibility(net ? View.GONE : View.VISIBLE);
    }

    private void refreshSelected() {
        tvSelected.setText("الطابعة المحفوظة: " + pm.describeSelected());
        renderDevices();
    }

    private void refreshStatus() {
        String label;
        int color;
        switch (pm.getState()) {
            case CONNECTED:
                label = "متصلة";
                color = R.color.success;
                break;
            case CONNECTING:
                label = "جارٍ الاتصال";
                color = R.color.warning;
                break;
            case ERROR:
                label = "خطأ";
                color = R.color.error;
                break;
            default:
                label = "غير متصلة";
                color = R.color.text_secondary;
        }
        tvStatus.setText(label + "\n" + pm.getStateMessage() + "\nالمحددة: " + pm.describeSelected());
        tvStatus.setTextColor(ContextCompat.getColor(this, color));
    }

    // ======================= الأذونات =======================

    private void updatePermHint() {
        tvPermHint.setVisibility(BtPermissions.hasAll(this) ? View.GONE : View.VISIBLE);
    }

    private void requestPerms(final Runnable ok) {
        if (BtPermissions.hasAll(this)) {
            if (ok != null) ok.run();
            return;
        }
        afterPerm = ok;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("إذن الأجهزة القريبة")
                .setMessage(BtPermissions.EXPLANATION)
                .setPositiveButton("متابعة", (x, w) -> permLauncher.launch(BtPermissions.missing(this)))
                .setNegativeButton(R.string.cancel, (x, w) -> afterPerm = null)
                .create();
        Ui.showRtl(d);
    }

    private void onPermResult() {
        updatePermHint();
        if (BtPermissions.hasAll(this)) {
            loadBonded();
            Runnable r = afterPerm;
            afterPerm = null;
            if (r != null) r.run();
            return;
        }
        afterPerm = null;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("لم يُمنح الإذن")
                .setMessage("بدون هذا الإذن لا يمكن البحث عن طابعات البلوتوث أو الاتصال بها. "
                        + "يمكنك المحاولة مرة أخرى أو منحه من إعدادات التطبيق، أو استخدام طابعة شبكية بدلاً من ذلك.")
                .setPositiveButton("حاول مجدداً", (x, w) -> requestPerms(null))
                .setNeutralButton("إعدادات التطبيق", (x, w) -> {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    startActivity(i);
                })
                .setNegativeButton(R.string.close, null)
                .create();
        Ui.showRtl(d);
    }

    // ======================= البحث عن الأجهزة =======================

    private void loadBonded() {
        if (isNetwork() || !BtPermissions.hasConnect(this)) return;
        for (DiscoveredPrinter p : pm.bondedDevices()) devices.putIfAbsent(p.address, p);
        renderDevices();
    }

    private void toggleScan() {
        if (scanning) {
            pm.stopDiscovery();
            setScanning(false);
            return;
        }
        requestPerms(this::startScan);
    }

    private void startScan() {
        setScanning(true);
        pm.startDiscovery(new PrinterManager.DiscoveryListener() {
            @Override
            public void onDeviceFound(DiscoveredPrinter p) {
                devices.putIfAbsent(p.address, p);
                renderDevices();
            }

            @Override
            public void onFinished() {
                setScanning(false);
                if (devices.isEmpty()) Ui.toast(PrinterSettingsActivity.this, "لم يُعثر على أجهزة قريبة");
            }

            @Override
            public void onError(String message) {
                setScanning(false);
                Ui.toast(PrinterSettingsActivity.this, message);
            }
        });
    }

    private void setScanning(boolean s) {
        scanning = s;
        if (btnScan != null) btnScan.setText(s ? "إيقاف البحث..." : "بحث عن أجهزة قريبة");
    }

    private void renderDevices() {
        if (deviceList == null) return;
        deviceList.removeAllViews();
        if (devices.isEmpty()) {
            deviceList.addView(UiKit.hint(this, "لا توجد أجهزة بعد. اضغط «بحث عن أجهزة قريبة»."));
            return;
        }
        String selected = st.get(AppSettings.PRINTER_BT_ADDRESS, "");
        for (final DiscoveredPrinter p : devices.values()) {
            View v = getLayoutInflater().inflate(R.layout.item_row, deviceList, false);
            String sub = p.address + (p.bonded ? "  •  مقترنة" : "  •  غير مقترنة") + (p.printerClass ? "  •  طابعة" : "");
            RowAdapter.Row r = new RowAdapter.Row(p.name, sub, null, p);
            if (p.address.equals(selected)) r.badge("محددة", R.color.success);
            RowAdapter.bind(v, r);
            v.setOnClickListener(x -> selectDevice(p));
            deviceList.addView(v);
        }
    }

    private void selectDevice(DiscoveredPrinter p) {
        pm.saveBluetoothPrinter(p.name, p.address);
        pm.disconnectAsync(null);
        refreshSelected();
        Ui.toast(this, "تم اختيار الطابعة «" + p.name + "». اضغط «اتصال» ثم «اختبار الطباعة».");
    }

    // ======================= الاتصال والاختبار =======================

    private void saveNetwork() {
        String ip = etIp.getText().toString().trim();
        if (ip.isEmpty() || !ip.matches("[A-Za-z0-9.\\-]+")) {
            etIp.setError("أدخل عنوان IP صحيحاً");
            return;
        }
        long port = etPort.getText().toString().trim().isEmpty() ? 9100 : Fmt.parseMoney(etPort.getText().toString());
        if (port < 1 || port > 65535) {
            etPort.setError("المنفذ من 1 إلى 65535");
            return;
        }
        pm.saveNetworkPrinter(ip, (int) port);
        pm.disconnectAsync(null);
        updatePanels();
        refreshSelected();
        Ui.toast(this, "تم حفظ الطابعة الشبكية");
    }

    private void withPermissionIfBluetooth(Runnable go) {
        if (!isNetwork() && !BtPermissions.hasConnect(this)) requestPerms(go);
        else go.run();
    }

    private void doConnect() {
        if (busy) return;
        if (!pm.hasSelectedPrinter()) {
            Ui.toast(this, isNetwork() ? "احفظ عنوان الطابعة الشبكية أولاً" : "اختر طابعة من القائمة أولاً");
            return;
        }
        withPermissionIfBluetooth(() -> {
            busy = true;
            pm.connectAsync((ok, msg) -> {
                busy = false;
                Ui.toast(this, msg);
                refreshStatus();
            });
        });
    }

    private void doDisconnect() {
        pm.disconnectAsync((ok, msg) -> {
            Ui.toast(this, msg);
            refreshStatus();
        });
    }

    private void doQueryStatus() {
        if (busy) return;
        if (!pm.hasSelectedPrinter()) {
            Ui.toast(this, "لم تُحدد طابعة");
            return;
        }
        withPermissionIfBluetooth(() -> {
            busy = true;
            pm.queryStatusAsync((ok, msg) -> {
                busy = false;
                refreshStatus();
                dialog(ok ? "حالة الطابعة" : "تعذر فحص الطابعة", msg);
            });
        });
    }

    private void doTestPrint() {
        if (busy || ReceiptPrinter.isBusy()) return;
        if (!savePrintSettings()) return;
        if (!pm.hasSelectedPrinter()) {
            dialog("لم تُحدد طابعة", "اختر طابعة بلوتوث أو احفظ عنوان طابعة شبكية أولاً.");
            return;
        }
        withPermissionIfBluetooth(() -> {
            busy = true;
            btnTest.setAlpha(0.5f);
            printer.printTest(r -> {
                busy = false;
                btnTest.setAlpha(1f);
                refreshStatus();
                dialog(r.success ? "أُرسل الاختبار" : "فشل الاختبار", r.message);
            });
        });
    }

    private boolean savePrintSettings() {
        int dots = 0;
        String w = etWidth.getText().toString().trim();
        if (!w.isEmpty()) {
            long v = Fmt.parseMoney(w);
            if (v < 200 || v > 832) {
                etWidth.setError("من 200 إلى 832");
                return false;
            }
            dots = (int) v;
        }
        int margin = 8;
        String m = etMargin.getText().toString().trim();
        if (!m.isEmpty()) {
            long v = Fmt.parseMoney(m);
            if (v < 0 || v > 60) {
                etMargin.setError("من 0 إلى 60");
                return false;
            }
            margin = (int) v;
        }
        int copies = 1;
        String c = etCopies.getText().toString().trim();
        if (!c.isEmpty()) {
            long v = Fmt.parseMoney(c);
            if (v < 1 || v > 5) {
                etCopies.setError("من 1 إلى 5");
                return false;
            }
            copies = (int) v;
        }
        st.setInt(ReceiptRenderer.KEY_WIDTH_DOTS, dots);
        st.setInt(AppSettings.PRINT_MARGIN_DOTS, margin);
        st.setInt(AppSettings.PRINT_COPIES, copies);
        st.setBool(AppSettings.PRINT_CUT, swCut.isChecked());
        return true;
    }

    private void dialog(String title, String msg) {
        if (isFinishing() || isDestroyed()) return;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(R.string.ok, null)
                .create();
        Ui.showRtl(d);
    }
}

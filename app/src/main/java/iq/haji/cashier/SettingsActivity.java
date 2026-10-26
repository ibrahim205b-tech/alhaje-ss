package iq.haji.cashier;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import iq.haji.cashier.data.AdminGuard;
import iq.haji.cashier.data.AppSettings;
import iq.haji.cashier.data.AuditLog;
import iq.haji.cashier.data.BackupManager;
import iq.haji.cashier.data.DriverStore;
import iq.haji.cashier.print.PrinterManager;
import iq.haji.cashier.util.AppException;
import iq.haji.cashier.util.Fmt;
import iq.haji.cashier.util.PinDialog;
import iq.haji.cashier.util.Ui;
import iq.haji.cashier.util.UiKit;

public class SettingsActivity extends PageActivity {

    private AppSettings st;
    private AdminGuard guard;

    private EditText etName;
    private EditText etAddress;
    private EditText etPhone;
    private EditText etFooter;
    private EditText etCashier;
    private EditText etFee;

    private SwitchCompat swTakeaway;
    private SwitchCompat swDelivery;
    private SwitchCompat swDineIn;
    private SwitchCompat swDiscount;

    private TextView tvPin;

    private final ActivityResultLauncher<String> createBackupLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.CreateDocument(
                            "application/octet-stream"),
                    uri -> {
                        if (uri == null) {
                            return;
                        }

                        this.<Boolean>bg(() -> {
                            BackupManager.exportBackupToUri(this, uri);
                            return true;
                        }, ok -> Ui.toast(
                                this,
                                "تم حفظ النسخة الاحتياطية في الموقع الذي اخترته"));
                    });

    private final ActivityResultLauncher<String[]> openBackupLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri == null) {
                            return;
                        }

                        confirmRestore(() -> this.<Boolean>bg(() -> {
                            BackupManager.restoreFromUri(this, uri);
                            return true;
                        }, ok -> restart()));
                    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setPageTitle("الإعدادات والنسخ الاحتياطي");

        st = new AppSettings(this);
        guard = new AdminGuard(this);

        LinearLayout h = UiKit.row(this);
        content.addView(
                h,
                new FrameLayout.LayoutParams(
                        UiKit.MATCH,
                        UiKit.MATCH));

        LinearLayout left = UiKit.column(this, h, 1, false);
        LinearLayout right = UiKit.column(this, h, 1, true);

        buildLeft(left);
        buildRight(right);
        loadValues();
    }

    // ==================== بيانات المطعم والطلبات ====================

    private void buildLeft(LinearLayout col) {
        LinearLayout restaurant = UiKit.card(this);

        restaurant.addView(
                UiKit.title(this, "بيانات المطعم وترويسة الفاتورة"));

        etName = field(
                restaurant,
                "اسم المطعم",
                InputType.TYPE_CLASS_TEXT);

        etAddress = field(
                restaurant,
                "العنوان",
                InputType.TYPE_CLASS_TEXT);

        etPhone = field(
                restaurant,
                "رقم الهاتف",
                InputType.TYPE_CLASS_PHONE);

        etFooter = field(
                restaurant,
                "ملاحظة ختامية في الفاتورة",
                InputType.TYPE_CLASS_TEXT);

        etCashier = field(
                restaurant,
                "اسم الموظف الافتراضي",
                InputType.TYPE_CLASS_TEXT);

        col.addView(
                restaurant,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 0));

        LinearLayout orders = UiKit.card(this);
        orders.addView(UiKit.title(this, "الطلبات"));

        swTakeaway = UiKit.toggle(this, "تفعيل «سفري»", true);
        swDelivery = UiKit.toggle(this, "تفعيل «دليفري»", true);
        swDineIn = UiKit.toggle(this, "تفعيل «داخل المطعم»", true);
        swDiscount = UiKit.toggle(this, "السماح بالخصم", true);

        orders.addView(
                swTakeaway,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 6));

        orders.addView(swDelivery);
        orders.addView(swDineIn);
        orders.addView(swDiscount);

        etFee = field(
                orders,
                "أجور التوصيل (د.ع) — تُضاف تلقائياً لكل فاتورة، وتُستخدم 1000 إذا تُرك فارغاً",
                InputType.TYPE_CLASS_NUMBER);

        col.addView(
                orders,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12));

        // السائقون: كل اسم هنا يظهر كزر في الشاشة الرئيسية لنسب الطلبات.
        LinearLayout drivers = UiKit.card(this);
        drivers.addView(UiKit.title(this, "السائقون"));
        drivers.addView(UiKit.hint(this,
                "اكتب اسم كل سائق في سطر مستقل. يظهر لكل سائق زر في أعلى شاشة الكاشير بجانب الوردية."));
        final EditText etDrivers = Ui.editText(this, "حسين\nعباس",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, joinDrivers());
        etDrivers.setSingleLine(false);
        etDrivers.setMinLines(4);
        etDrivers.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        drivers.addView(etDrivers, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
        TextView saveDrivers = UiKit.button(this, "حفظ أسماء السائقين", false);
        saveDrivers.setOnClickListener(v -> {
            java.util.List<String> names = new java.util.ArrayList<>();
            for (String line : etDrivers.getText().toString().split("\\r?\\n")) {
                names.add(line);
            }
            DriverStore.save(this, names);
            etDrivers.setText(joinDrivers());
            Ui.toast(this, "تم حفظ أسماء السائقين");
        });
        drivers.addView(saveDrivers, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
        col.addView(drivers, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12));

        TextView save = UiKit.button(this, "حفظ الإعدادات", true);
        save.setOnClickListener(v -> save());

        col.addView(
                save,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12));
    }

    private String joinDrivers() {
        StringBuilder sb = new StringBuilder();
        for (String n : DriverStore.load(this)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(n);
        }
        return sb.toString();
    }

    private EditText field(
            LinearLayout parent,
            String label,
            int type) {

        parent.addView(
                UiKit.label(this, label),
                UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));

        EditText et = Ui.editText(this, "", type, "");

        parent.addView(
                et,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 4));

        return et;
    }

    private void loadValues() {
        etName.setText(st.restaurantName());

        etAddress.setText(
                st.get(AppSettings.RESTAURANT_ADDRESS, ""));

        etPhone.setText(
                st.get(AppSettings.RESTAURANT_PHONE, ""));

        etFooter.setText(
                st.get(AppSettings.RECEIPT_FOOTER, "شكراً لزيارتكم"));

        etCashier.setText(
                st.get(AppSettings.CASHIER_NAME, "الكاشير"));

        swTakeaway.setChecked(
                st.getBool(AppSettings.ENABLE_TAKEAWAY, true));

        swDelivery.setChecked(
                st.getBool(AppSettings.ENABLE_DELIVERY, true));

        swDineIn.setChecked(
                st.getBool(AppSettings.ENABLE_DINE_IN, true));

        swDiscount.setChecked(
                st.getBool(AppSettings.ALLOW_DISCOUNT, true));

        long fee = st.getLong(AppSettings.DEFAULT_DELIVERY_FEE, 0);

        etFee.setText(fee > 0 ? String.valueOf(fee) : "");

        refreshPin();
    }

    private void save() {
        String name = etName.getText().toString().trim();

        if (name.isEmpty()) {
            etName.setError("أدخل اسم المطعم");
            return;
        }

        if (!swTakeaway.isChecked()
                && !swDelivery.isChecked()
                && !swDineIn.isChecked()) {

            Ui.toast(this, "فعّل نوع طلب واحداً على الأقل");
            return;
        }

        long fee = 0;
        String f = etFee.getText().toString().trim();

        if (!f.isEmpty()) {
            fee = Fmt.parseMoney(f);

            if (fee < 0) {
                etFee.setError("مبلغ غير صحيح");
                return;
            }
        }

        st.set(AppSettings.RESTAURANT_NAME, name);

        st.set(
                AppSettings.RESTAURANT_ADDRESS,
                etAddress.getText().toString().trim());

        st.set(
                AppSettings.RESTAURANT_PHONE,
                etPhone.getText().toString().trim());

        st.set(
                AppSettings.RECEIPT_FOOTER,
                etFooter.getText().toString().trim());

        st.set(
                AppSettings.CASHIER_NAME,
                etCashier.getText().toString().trim());

        st.setBool(
                AppSettings.ENABLE_TAKEAWAY,
                swTakeaway.isChecked());

        st.setBool(
                AppSettings.ENABLE_DELIVERY,
                swDelivery.isChecked());

        st.setBool(
                AppSettings.ENABLE_DINE_IN,
                swDineIn.isChecked());

        st.setBool(
                AppSettings.ALLOW_DISCOUNT,
                swDiscount.isChecked());

        st.setLong(AppSettings.DEFAULT_DELIVERY_FEE, fee);

        Ui.toast(this, "تم حفظ الإعدادات");
    }

    // ==================== الأمان والنسخ الاحتياطي ====================

    private void buildRight(LinearLayout col) {
        LinearLayout sec = UiKit.card(this);

        sec.addView(UiKit.title(this, "رمز المدير"));

        sec.addView(
                UiKit.hint(
                        this,
                        "اختياري. عند تعيينه يُطلب لإلغاء الطلبات المحفوظة " +
                        "وتعديل الأسعار وحذف الأصناف والأقسام وإغلاق الوردية " +
                        "واستعادة النسخ."));

        tvPin = UiKit.text(
                this,
                "",
                15,
                R.color.text_primary,
                true);

        sec.addView(
                tvPin,
                UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 8));

        TextView b = UiKit.button(
                this,
                "تعيين / تغيير / تعطيل الرمز",
                false);

        b.setOnClickListener(v -> changePin());

        sec.addView(
                b,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 10));

        col.addView(
                sec,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 0));

        LinearLayout bk = UiKit.card(this);

        bk.addView(
                UiKit.title(this, "النسخ الاحتياطي والاستعادة"));

        bk.addView(
                UiKit.hint(
                        this,
                        "تُنشأ نسخة تلقائية كل 24 ساعة عند فتح التطبيق " +
                        "وتُحفظ آخر 15 نسخة داخل الجهاز. احتفظ بنسخة " +
                        "خارج الجهاز بانتظام."));

        TextView b1 = UiKit.button(
                this,
                "حفظ نسخة خارج الجهاز",
                true);

        b1.setOnClickListener(v -> createBackupLauncher.launch(
                "alhaje_backup_"
                        + new SimpleDateFormat(
                                "yyyyMMdd_HHmm",
                                Locale.US).format(new Date())
                        + ".db"));

        bk.addView(
                b1,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12));

        TextView b2 = UiKit.button(
                this,
                "إنشاء نسخة داخلية الآن",
                false);

        b2.setOnClickListener(v ->
                this.<File>bg(
                        () -> BackupManager.createBackup(this, "backup_"),
                        f -> Ui.toast(
                                this,
                                "تم إنشاء النسخة: " + f.getName())));

        bk.addView(
                b2,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));

        TextView b3 = UiKit.button(
                this,
                "استعادة من ملف",
                false);

        b3.setOnClickListener(v ->
                openBackupLauncher.launch(new String[]{"*/*"}));

        bk.addView(
                b3,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));

        TextView b4 = UiKit.button(
                this,
                "استعادة من نسخة داخل الجهاز",
                false);

        b4.setOnClickListener(v -> showInternalBackups());

        bk.addView(
                b4,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));

        col.addView(
                bk,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12));

        LinearLayout lg = UiKit.card(this);

        lg.addView(UiKit.title(this, "سجل العمليات"));

        lg.addView(
                UiKit.hint(
                        this,
                        "الإلغاء وتعديل الأسعار والحذف وإغلاق الوردية والاستعادة."));

        TextView b5 = UiKit.button(
                this,
                "عرض آخر 100 عملية",
                false);

        b5.setOnClickListener(v -> showAudit());

        lg.addView(
                b5,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 10));

        col.addView(
                lg,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12));
    }

    private void refreshPin() {
        tvPin.setText(
                guard.isEnabled()
                        ? "الحالة: مفعّل"
                        : "الحالة: غير مفعّل");
    }

    private void changePin() {
        PinDialog.require(this, () -> {
            final EditText et = Ui.editText(
                    this,
                    "4 إلى 8 أرقام (فارغ = تعطيل)",
                    InputType.TYPE_CLASS_NUMBER
                            | InputType.TYPE_NUMBER_VARIATION_PASSWORD,
                    "");

            final AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("رمز المدير الجديد")
                    .setMessage("اترك الحقل فارغاً لتعطيل الحماية.")
                    .setView(Ui.wrap(this, et))
                    .setPositiveButton(R.string.save, null)
                    .setNegativeButton(R.string.cancel, null)
                    .create();

            d.setOnShowListener(x ->
                    d.getButton(AlertDialog.BUTTON_POSITIVE)
                            .setOnClickListener(v -> {
                                try {
                                    guard.setPin(
                                            et.getText().toString());

                                    d.dismiss();
                                    refreshPin();

                                    Ui.toast(
                                            this,
                                            guard.isEnabled()
                                                    ? "تم تعيين الرمز"
                                                    : "تم تعطيل الرمز");
                                } catch (AppException e) {
                                    et.setError(e.getMessage());
                                }
                            }));

            Ui.showRtl(d);
        });
    }

    private void showInternalBackups() {
        final List<File> files = BackupManager.listBackups(this);

        if (files.isEmpty()) {
            Ui.toast(this, "لا توجد نسخ داخل الجهاز");
            return;
        }

        String[] items = new String[files.size()];

        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);

            items[i] = Fmt.dateTime(f.lastModified())
                    + "  —  "
                    + f.getName();
        }

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("اختر نسخة للاستعادة")
                .setItems(items, (x, which) -> {
                    final File f = files.get(which);

                    confirmRestore(() ->
                            this.<Boolean>bg(() -> {
                                BackupManager.restoreFromFile(this, f);
                                return true;
                            }, ok -> restart()));
                })
                .setNegativeButton(R.string.close, null)
                .create();

        Ui.showRtl(d);
    }

    private void confirmRestore(final Runnable doIt) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("استعادة نسخة احتياطية")
                .setMessage(
                        "ستُستبدل كل البيانات الحالية (الطلبات والأصناف " +
                        "والورديات والإعدادات) بمحتوى النسخة. تُحفظ نسخة " +
                        "أمان من بياناتك الحالية تلقائياً قبل الاستبدال، " +
                        "ويُعاد تشغيل التطبيق بعدها. متابعة؟")
                .setPositiveButton(
                        "استعادة",
                        (x, w) -> PinDialog.require(this, doIt))
                .setNegativeButton(R.string.cancel, null)
                .create();

        Ui.showRtl(d);
    }

    private void restart() {
        Ui.toast(this, "تمت الاستعادة. سيُعاد تشغيل التطبيق.");

        PrinterManager.get(this).disconnectAsync(null);

        Intent i = new Intent(this, SplashActivity.class);

        i.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        startActivity(i);
        finish();
    }

    private void showAudit() {
        List<String> lines = AuditLog.recent(this, 100);

        StringBuilder sb = new StringBuilder();

        for (String s : lines) {
            sb.append(s).append("\n\n");
        }

        TextView tv = new TextView(this);

        tv.setText(
                lines.isEmpty()
                        ? "لا توجد عمليات مسجلة"
                        : sb.toString());

        tv.setTextSize(14);
        tv.setTextAlignment(
                android.view.View.TEXT_ALIGNMENT_VIEW_START);

        int p = Ui.dp(this, 20);
        tv.setPadding(p, p, p, p);

        ScrollView sv = new ScrollView(this);
        sv.addView(tv);

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("سجل العمليات")
                .setView(sv)
                .setPositiveButton(R.string.close, null)
                .create();

        Ui.showRtl(d);
    }
}

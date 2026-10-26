package iq.haji.cashier;

import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import iq.haji.cashier.data.DriverStore;
import iq.haji.cashier.data.OrderRepository;
import iq.haji.cashier.data.ShiftRepository;
import iq.haji.cashier.model.Order;
import iq.haji.cashier.model.OrderStatus;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.print.DriverReportPrinter;
import iq.haji.cashier.print.PrintException;
import iq.haji.cashier.print.ReceiptRenderer;
import iq.haji.cashier.util.AppException;
import iq.haji.cashier.util.Fmt;
import iq.haji.cashier.util.Ui;
import iq.haji.cashier.util.UiKit;

/**
 * تقارير السائقين (من الإدارة): زر لكل سائق، الطلبات غير المنسوبة، طلبات السائق المختار
 * مع معاينة الفاتورة كما تخرج من الطابعة، وطباعة وردية السائق وتقرير كل الحركات.
 * النسب نفسه يتم من أزرار السائقين في أعلى شاشة الكاشير.
 * النطاق: الوردية المفتوحة (أو آخر وردية إذا لم تكن هناك وردية مفتوحة).
 */
public class DriversActivity extends PageActivity {

    private OrderRepository orders;
    private Shift shift;
    private List<String> drivers = new ArrayList<>();
    private String selected;

    private LinearLayout driverButtons;
    private LinearLayout unassignedBox;
    private LinearLayout driverOrdersBox;
    private TextView tvUnassignedTitle;
    private TextView tvDriverTitle;
    private TextView tvDriverSummary;
    private TextView tvScope;
    private ImageView preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageTitle("تقارير السائقين");
        orders = new OrderRepository(this);

        addAction("وردية السائق", this::printDriverShift);
        addAction("تقرير كل السائقين", this::printAllDrivers);

        LinearLayout root = UiKit.vertical(this);
        content.addView(root, new FrameLayout.LayoutParams(UiKit.MATCH, UiKit.MATCH));

        tvScope = UiKit.text(this, "", 13, R.color.text_secondary, false);
        root.addView(tvScope);

        driverButtons = UiKit.row(this);
        root.addView(UiKit.scrollRow(this, driverButtons), UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));

        LinearLayout body = UiKit.row(this);
        LinearLayout.LayoutParams bl = UiKit.lp(this, UiKit.MATCH, 0, 10);
        bl.weight = 1f;
        root.addView(body, bl);

        // العمود 1: الطلبات غير المنسوبة
        LinearLayout c1 = card(body, 1.1f, false);
        tvUnassignedTitle = UiKit.title(this, "");
        c1.addView(tvUnassignedTitle);
        unassignedBox = scrollList(c1);

        // العمود 2: طلبات السائق المختار
        LinearLayout c2 = card(body, 1f, true);
        tvDriverTitle = UiKit.title(this, "");
        c2.addView(tvDriverTitle);
        tvDriverSummary = UiKit.text(this, "", 13, R.color.text_secondary, false);
        c2.addView(tvDriverSummary);
        driverOrdersBox = scrollList(c2);

        // العمود 3: معاينة الفاتورة
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundResource(R.drawable.bg_card);
        FrameLayout frame = new FrameLayout(this);
        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setBackgroundColor(0xFFFFFFFF);
        frame.addView(preview, new FrameLayout.LayoutParams(Ui.dp(this, 360), UiKit.WRAP, Gravity.CENTER_HORIZONTAL));
        int p = Ui.dp(this, 10);
        frame.setPadding(p, p, p, p);
        sv.addView(frame);
        body.addView(sv, UiKit.weight(this, 1.2f, 10));

        reload();
    }

    private LinearLayout card(LinearLayout parent, float weight, boolean margin) {
        LinearLayout c = UiKit.card(this);
        parent.addView(c, UiKit.weight(this, weight, margin ? 10 : 0));
        ((LinearLayout.LayoutParams) c.getLayoutParams()).height = UiKit.MATCH;
        return c;
    }

    private LinearLayout scrollList(LinearLayout parent) {
        ScrollView sv = new ScrollView(this);
        LinearLayout box = UiKit.vertical(this);
        sv.addView(box, new ScrollView.LayoutParams(UiKit.MATCH, UiKit.WRAP));
        LinearLayout.LayoutParams lp = UiKit.lp(this, UiKit.MATCH, 0, 8);
        lp.weight = 1f;
        parent.addView(sv, lp);
        return box;
    }

    // ---------------- البيانات ----------------

    private void reload() {
        ShiftRepository shifts = new ShiftRepository(this);
        shift = shifts.getOpenShift();
        boolean open = shift != null;
        if (shift == null) {
            List<Shift> recent = shifts.listShifts(1);
            shift = recent.isEmpty() ? null : recent.get(0);
        }
        tvScope.setText(shift == null ? "لا توجد ورديات بعد"
                : (open ? "الوردية المفتوحة #" + shift.id : "آخر وردية #" + shift.id + " (مغلقة — للعرض والطباعة فقط)")
                + "  •  بدأت " + Fmt.dateTime(shift.startedAt));

        drivers = DriverStore.load(this);
        if (selected != null && !drivers.contains(selected)) {
            selected = null;
        }
        if (selected == null && !drivers.isEmpty()) {
            selected = drivers.get(0);
        }
        buildDriverButtons();

        List<Order> all = shiftOrders();
        List<Order> unassigned = new ArrayList<>();
        List<Order> mine = new ArrayList<>();
        for (Order o : all) {
            String d = o.driver == null ? "" : o.driver.trim();
            if (d.isEmpty()) {
                unassigned.add(o);
            } else if (d.equals(selected)) {
                mine.add(o);
            }
        }
        renderUnassigned(unassigned);
        renderDriverOrders(mine);
    }

    /** طلبات الوردية غير الملغاة. */
    private List<Order> shiftOrders() {
        List<Order> out = new ArrayList<>();
        if (shift == null) {
            return out;
        }
        for (Order o : orders.listOrders(shift.id, null, 0, 0, null, 2000)) {
            if (!OrderStatus.CANCELLED.equals(o.status)) {
                out.add(o);
            }
        }
        return out;
    }

    private void buildDriverButtons() {
        driverButtons.removeAllViews();
        if (drivers.isEmpty()) {
            driverButtons.addView(UiKit.text(this, "لا يوجد سائقون. أضفهم من الإعدادات ← السائقون.",
                    15, R.color.warning, true));
            return;
        }
        for (final String name : drivers) {
            TextView b = new TextView(this);
            b.setText(name);
            b.setTextSize(17);
            b.setTypeface(Typeface.DEFAULT_BOLD);
            b.setGravity(Gravity.CENTER);
            b.setMinWidth(Ui.dp(this, 120));
            b.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), 0);
            boolean on = name.equals(selected);
            b.setBackgroundResource(on ? R.drawable.bg_button_primary : R.drawable.bg_card);
            b.setTextColor(ContextCompat.getColor(this, on ? R.color.brand_navy : R.color.text_primary));
            b.setOnClickListener(v -> {
                selected = name;
                reload();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UiKit.WRAP, Ui.dp(this, 52));
            lp.setMarginEnd(Ui.dp(this, 8));
            driverButtons.addView(b, lp);
        }
    }

    private boolean shiftOpen() {
        return shift != null && !shift.closed;
    }

    private void renderUnassigned(List<Order> list) {
        tvUnassignedTitle.setText("طلبات بلا سائق (" + list.size() + ")");
        unassignedBox.removeAllViews();
        if (list.isEmpty()) {
            unassignedBox.addView(UiKit.text(this, "كل الطلبات منسوبة لسائقين", 14, R.color.text_hint, false));
            return;
        }
        for (final Order o : list) {
            LinearLayout row = UiKit.row(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            TextView no = UiKit.text(this, "فاتورة #" + o.id, 18, R.color.text_primary, true);
            no.setOnClickListener(v -> showReceipt(o.id));
            row.addView(no, new LinearLayout.LayoutParams(0, UiKit.WRAP, 1f));
            unassignedBox.addView(row);
            unassignedBox.addView(divider());
        }
    }

    private void renderDriverOrders(List<Order> list) {
        driverOrdersBox.removeAllViews();
        if (selected == null) {
            tvDriverTitle.setText("اختر سائقاً");
            tvDriverSummary.setText("");
            preview.setImageDrawable(null);
            return;
        }
        long total = 0;
        long fees = 0;
        for (Order o : list) {
            total += o.total;
            fees += o.deliveryFee;
        }
        tvDriverTitle.setText("طلبات " + selected + " (" + list.size() + ")");
        tvDriverSummary.setText("المبالغ: " + Fmt.money(total) + "  •  أجور التوصيل: " + Fmt.money(fees)
                + "\nاضغط الطلب لعرض فاتورته، واضغط مطولاً لإلغاء نسبه.");
        if (list.isEmpty()) {
            driverOrdersBox.addView(UiKit.text(this, "لا توجد طلبات منسوبة لهذا السائق", 14, R.color.text_hint, false));
            preview.setImageDrawable(null);
            return;
        }
        for (final Order o : list) {
            LinearLayout row = UiKit.row(this);
            row.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
            row.addView(UiKit.text(this, "فاتورة #" + o.id, 17, R.color.text_primary, true),
                    new LinearLayout.LayoutParams(0, UiKit.WRAP, 1f));
            row.addView(UiKit.text(this, Fmt.time(o.createdAt) + "   " + Fmt.money(o.total), 14, R.color.text_secondary, false));
            row.setOnClickListener(v -> showReceipt(o.id));
            row.setOnLongClickListener(v -> {
                unassign(o);
                return true;
            });
            driverOrdersBox.addView(row);
            driverOrdersBox.addView(divider());
        }
        showReceipt(list.get(0).id);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(ContextCompat.getColor(this, R.color.stroke));
        v.setLayoutParams(new LinearLayout.LayoutParams(UiKit.MATCH, Ui.dp(this, 1)));
        return v;
    }

    /** يعرض الفاتورة بنفس الصورة التي تُرسل للطابعة. */
    private void showReceipt(long orderId) {
        Order o = orders.getOrder(orderId);
        if (o == null) {
            return;
        }
        try {
            Bitmap b = new ReceiptRenderer(this).render(o);
            preview.setImageBitmap(b);
        } catch (PrintException e) {
            Ui.toast(this, e.getMessage());
        }
    }

    // ---------------- النسب ----------------

    private void unassign(final Order o) {
        if (!shiftOpen()) {
            Ui.toast(this, "الوردية مغلقة، لا يمكن تعديل طلباتها");
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("فاتورة #" + o.id)
                .setMessage("إلغاء نسب هذا الطلب إلى " + o.driver + "؟ سيعود إلى «طلبات بلا سائق».")
                .setPositiveButton("إلغاء النسب", (x, w) -> {
                    try {
                        orders.updateDriver(o.id, "");
                        reload();
                    } catch (AppException e) {
                        Ui.toast(this, e.getMessage());
                    }
                })
                .setNegativeButton(R.string.back, null)
                .create();
        Ui.showRtl(d);
    }

    // ---------------- التقارير والطباعة ----------------

    private List<Order> ordersOf(String driver, List<Order> all) {
        List<Order> out = new ArrayList<>();
        for (Order o : all) {
            if (o.driver != null && o.driver.trim().equals(driver)) {
                out.add(o);
            }
        }
        // من الأقدم للأحدث في التقرير المطبوع.
        java.util.Collections.reverse(out);
        return out;
    }

    private void printDriverShift() {
        if (selected == null) {
            Ui.toast(this, "اختر سائقاً أولاً");
            return;
        }
        DriverReportPrinter p = new DriverReportPrinter(this);
        try {
            showReport("وردية " + selected, p, p.renderDriver(shift, selected, ordersOf(selected, shiftOrders())));
        } catch (PrintException e) {
            Ui.toast(this, e.getMessage());
        }
    }

    private void printAllDrivers() {
        List<Order> all = shiftOrders();
        Map<String, List<Order>> by = new LinkedHashMap<>();
        for (String d : drivers) {
            by.put(d, ordersOf(d, all));
        }
        List<Order> unassigned = new ArrayList<>();
        for (Order o : all) {
            String d = o.driver == null ? "" : o.driver.trim();
            if (d.isEmpty()) {
                unassigned.add(o);
            } else if (!by.containsKey(d)) {
                // سائق حُذف اسمه من القائمة لكن له طلبات مسجلة
                by.put(d, ordersOf(d, all));
            }
        }
        java.util.Collections.reverse(unassigned);
        DriverReportPrinter p = new DriverReportPrinter(this);
        try {
            showReport("تقرير كل السائقين", p, p.renderAll(shift, by, unassigned));
        } catch (PrintException e) {
            Ui.toast(this, e.getMessage());
        }
    }

    /** معاينة التقرير كما سيُطبع، مع زر طباعة. */
    private void showReport(String title, final DriverReportPrinter p, final Bitmap bmp) {
        ImageView img = new ImageView(this);
        img.setAdjustViewBounds(true);
        img.setImageBitmap(bmp);
        img.setBackgroundColor(0xFFFFFFFF);
        ScrollView sv = new ScrollView(this);
        FrameLayout f = new FrameLayout(this);
        f.addView(img, new FrameLayout.LayoutParams(Ui.dp(this, 360), UiKit.WRAP, Gravity.CENTER_HORIZONTAL));
        sv.addView(f);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sv)
                .setPositiveButton("طباعة", (x, w) -> p.print(bmp, (ok, msg) -> Ui.toast(this, msg)))
                .setNegativeButton(R.string.close, null)
                .create();
        Ui.showRtl(d);
    }
}

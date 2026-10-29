package iq.haji.cashier;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

import iq.haji.cashier.data.AdminGuard;
import iq.haji.cashier.data.OrderRepository;
import iq.haji.cashier.data.ShiftRepository;
import iq.haji.cashier.model.Order;
import iq.haji.cashier.model.OrderLine;
import iq.haji.cashier.model.OrderStatus;
import iq.haji.cashier.model.OrderType;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.model.StatusLogEntry;
import iq.haji.cashier.print.PrinterManager;
import iq.haji.cashier.print.ReceiptPrinter;
import iq.haji.cashier.util.AppException;
import iq.haji.cashier.util.Fmt;
import iq.haji.cashier.util.PinDialog;
import iq.haji.cashier.util.Ui;
import iq.haji.cashier.util.UiKit;

public class OrderDetailsActivity extends PageActivity {
    private long orderId;
    private OrderRepository repo;
    private LinearLayout left, right;
    private boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        orderId = getIntent().getLongExtra("order_id", 0);
        if (orderId == 0) {
            finish();
            return;
        }
        repo = new OrderRepository(this);
        setPageTitle("تفاصيل الطلب");
        LinearLayout h = UiKit.row(this);
        content.addView(h, new android.widget.FrameLayout.LayoutParams(UiKit.MATCH, UiKit.MATCH));
        left = UiKit.column(this, h, 5, false);
        right = UiKit.column(this, h, 4, true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (repo != null) render();
    }

    private void render() {
        final Order o = repo.getOrder(orderId);
        if (o == null) {
            Ui.toast(this, "الطلب غير موجود");
            finish();
            return;
        }
        left.removeAllViews();
        right.removeAllViews();

        // ---- معلومات الطلب ----
        LinearLayout info = UiKit.card(this);
        info.addView(UiKit.title(this, "الطلب رقم #" + o.displayNo()));
        info.addView(UiKit.kv(this, "الحالة", OrderStatus.label(o.status), false));
        info.addView(UiKit.kv(this, "النوع", OrderType.label(o.type), false));
        info.addView(UiKit.kv(this, "التاريخ والوقت", Fmt.dateTime(o.createdAt), false));
        info.addView(UiKit.kv(this, "الوردية", "#" + o.shiftId, false));
        if (notEmpty(o.cashierName)) info.addView(UiKit.kv(this, "الموظف", o.cashierName, false));
        if (notEmpty(o.customerName)) info.addView(UiKit.kv(this, "الزبون", o.customerName, false));
        if (notEmpty(o.phone)) info.addView(UiKit.kv(this, "الهاتف", o.phone, false));
        if (OrderType.DELIVERY.equals(o.type)) {
            if (notEmpty(o.address)) info.addView(UiKit.kv(this, "العنوان", o.address, false));
            info.addView(UiKit.kv(this, "السائق", notEmpty(o.driver) ? o.driver : "غير محدد", false));
        }
        if (notEmpty(o.paymentMethod)) info.addView(UiKit.kv(this, "طريقة الدفع", o.paymentMethod, false));
        if (notEmpty(o.notes)) info.addView(UiKit.kv(this, "ملاحظات", o.notes, false));
        info.addView(UiKit.kv(this, "مرات الطباعة", String.valueOf(o.printCount), false));
        left.addView(info, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 0));

        // ---- الأصناف والمجاميع ----
        LinearLayout items = UiKit.card(this);
        items.addView(UiKit.title(this, "الأصناف"));
        for (OrderLine l : o.lines) {
            items.addView(UiKit.kv(this, l.name + "  ×" + l.qty, Fmt.money(l.total()), false));
            if (notEmpty(l.note)) {
                items.addView(UiKit.text(this, "• " + l.note, 13, R.color.brand_red, false));
            }
        }
        items.addView(UiKit.kv(this, "المجموع", Fmt.money(o.subtotal), false),
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 10));
        if (o.discount > 0) items.addView(UiKit.kv(this, "الخصم", "- " + Fmt.money(o.discount), false));
        if (o.deliveryFee > 0) items.addView(UiKit.kv(this, "أجور التوصيل", Fmt.money(o.deliveryFee), false));
        items.addView(UiKit.kv(this, "الإجمالي", Fmt.money(o.total), true));
        left.addView(items, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12));

        // ---- الإجراءات ----
        Shift sh = new ShiftRepository(this).getShift(o.shiftId);
        boolean shiftOpen = sh != null && !sh.closed;
        boolean cancelled = OrderStatus.CANCELLED.equals(o.status);
        boolean pendingPrint = OrderStatus.PENDING_PRINT.equals(o.status);

        LinearLayout act = UiKit.card(this);
        act.addView(UiKit.title(this, "الإجراءات"));
        if (!cancelled) {
            TextView b = UiKit.button(this, pendingPrint ? "إعادة محاولة الطباعة" : "إعادة طباعة نسخة", true);
            b.setOnClickListener(v -> onPrint(o));
            act.addView(b, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 10));
            if (pendingPrint) {
                act.addView(UiKit.hint(this, "الطلب محفوظ ولم تتأكد طباعته بعد. لن تُنشأ فاتورة جديدة عند الإعادة."));
            }
        }
        if (shiftOpen && !cancelled && !pendingPrint) {
            TextView e = UiKit.button(this, "✎ تعديل الطلب (رمز المدير)", false);
            e.setOnClickListener(v -> onEdit());
            act.addView(e, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
        }
        if (shiftOpen) {
            if (OrderRepository.canChange(o.status, OrderStatus.READY)) {
                act.addView(statusButton("تحويل إلى: جاهز للتسليم", OrderStatus.READY, false),
                        UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
            }
            if (OrderRepository.canChange(o.status, OrderStatus.COMPLETED)) {
                act.addView(statusButton("تحويل إلى: مكتمل", OrderStatus.COMPLETED, false),
                        UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
            }
            if (OrderType.DELIVERY.equals(o.type) && !cancelled && !OrderStatus.COMPLETED.equals(o.status)) {
                TextView d = UiKit.button(this, "تعيين السائق", false);
                d.setOnClickListener(v -> editDriver(o));
                act.addView(d, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
            }
            if (OrderRepository.canChange(o.status, OrderStatus.CANCELLED)) {
                TextView c = UiKit.button(this, "إلغاء الطلب", false);
                c.setOnClickListener(v -> onCancel(o));
                act.addView(c, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
            }
        } else {
            act.addView(UiKit.hint(this, "وردية هذا الطلب مغلقة، فلا يمكن تعديل حالته."),
                    UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
        }
        right.addView(act, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 0));

        // ---- سجل الحالات ----
        LinearLayout log = UiKit.card(this);
        log.addView(UiKit.title(this, "سجل تغييرات الحالة"));
        List<StatusLogEntry> entries = repo.getStatusLog(orderId);
        for (StatusLogEntry e : entries) {
            String line = Fmt.dateTime(e.changedAt) + "  —  " + OrderStatus.label(e.status);
            TextView t = UiKit.text(this, line, 14, R.color.text_primary, true);
            log.addView(t, UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 10));
            if (notEmpty(e.note)) log.addView(UiKit.text(this, e.note, 13, R.color.text_secondary, false));
        }
        right.addView(log, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12));
    }

    private TextView statusButton(String text, final String status, boolean primary) {
        TextView b = UiKit.button(this, text, primary);
        b.setOnClickListener(v -> {
            try {
                repo.setStatus(orderId, status, null);
                render();
            } catch (AppException e) {
                Ui.toast(this, e.getMessage());
            }
        });
        return b;
    }

    private void onPrint(final Order o) {
        if (busy || ReceiptPrinter.isBusy()) return;
        if (!PrinterManager.get(this).hasSelectedPrinter()) {
            msg("لم تُحدد طابعة", "اختر طابعة من شاشة إعدادات الطابعة أولاً.");
            return;
        }
        if (OrderStatus.PENDING_PRINT.equals(o.status)) {
            doPrint();
            return;
        }
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("إعادة طباعة")
                .setMessage("سيُطبع نسخة إضافية من الفاتورة رقم #" + o.displayNo() + " (لن تُنشأ فاتورة جديدة).")
                .setPositiveButton("طباعة", (x, w) -> doPrint())
                .setNegativeButton(R.string.cancel, null)
                .create();
        Ui.showRtl(d);
    }

    private void doPrint() {
        busy = true;
        Ui.toast(this, "جارٍ الإرسال إلى الطابعة...");
        new ReceiptPrinter(this).reprint(orderId, r -> {
            busy = false;
            if (isFinishing() || isDestroyed()) return;
            render();
            msg(r.success ? "تم الإرسال" : "لم تتم الطباعة", r.message);
        });
    }

    /** يفتح الطلب في شاشة الكاشير للتعديل بعد إدخال رمز المدير. */
    private void onEdit() {
        if (!new AdminGuard(this).isEnabled()) {
            msg("رمز المدير غير مُعيّن",
                    "تعديل الطلبات المطبوعة يحتاج رمز المدير. عيّن الرمز من الإعدادات أولاً ثم أعد المحاولة.");
            return;
        }
        PinDialog.require(this, () -> {
            android.content.Intent i = new android.content.Intent(this, MainActivity.class);
            i.setAction(MainActivity.ACTION_EDIT_ORDER);
            i.putExtra("order_id", orderId);
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
            finish();
        });
    }

    private void onCancel(final Order o) {
        final EditText reason = Ui.editText(this, "سبب الإلغاء (اختياري)", InputType.TYPE_CLASS_TEXT, "");
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("إلغاء الطلب رقم #" + o.displayNo())
                .setMessage("يبقى الطلب في السجل بحالة «ملغى» ولا يُحتسب في المبيعات.")
                .setView(Ui.wrap(this, reason))
                .setPositiveButton("إلغاء الطلب", (x, w) -> {
                    final String why = reason.getText().toString().trim();
                    PinDialog.require(this, () -> {
                        try {
                            repo.cancelOrder(orderId, why);
                            render();
                        } catch (AppException e) {
                            Ui.toast(this, e.getMessage());
                        }
                    });
                })
                .setNegativeButton(R.string.back, null)
                .create();
        Ui.showRtl(d);
    }

    private void editDriver(Order o) {
        final EditText et = Ui.editText(this, "اسم السائق", InputType.TYPE_CLASS_TEXT, o.driver);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("السائق")
                .setView(Ui.wrap(this, et))
                .setPositiveButton(R.string.save, (x, w) -> {
                    try {
                        repo.updateDriver(orderId, et.getText().toString());
                        render();
                    } catch (AppException e) {
                        Ui.toast(this, e.getMessage());
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        Ui.showRtl(d);
    }

    private void msg(String title, String message) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.ok, null)
                .create();
        Ui.showRtl(d);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}

package iq.haji.cashier.ui;

import android.app.Activity;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.util.List;

import iq.haji.cashier.data.CsvExporter;
import iq.haji.cashier.data.ReportRepository;
import iq.haji.cashier.data.ShiftRepository;
import iq.haji.cashier.model.SalesSummary;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.model.TopItem;
import iq.haji.cashier.print.DailySalesReportPrinter;
import iq.haji.cashier.util.Fmt;
import iq.haji.cashier.util.Ui;

/** تقرير وردية (مفتوحة أو مغلقة) مع طباعته وتصدير CSV لطلباتها. */
public final class ShiftReportDialog {
    private ShiftReportDialog() {}

    public static void show(final Activity a, final long shiftId) {
        final Shift s = new ShiftRepository(a).getShift(shiftId);
        if (s == null) {
            Ui.toast(a, "الوردية غير موجودة");
            return;
        }
        ReportRepository rr = new ReportRepository(a);
        SalesSummary live = rr.summaryForShift(shiftId);
        int orders = s.closed ? s.ordersCount : live.ordersCount;
        int cancelled = s.closed ? s.cancelledCount : live.cancelledCount;
        long sales = s.closed ? s.salesTotal : live.salesTotal;
        long disc = s.closed ? s.discountsTotal : live.discountsTotal;
        long deliv = s.closed ? s.deliveryTotal : live.deliveryTotal;
        long expected = s.openingCash + sales;

        StringBuilder sb = new StringBuilder();
        sb.append("الحالة: ").append(s.closed ? "مغلقة" : "مفتوحة").append('\n');
        sb.append("الموظف: ").append(s.cashierName == null ? "" : s.cashierName).append('\n');
        sb.append("البداية: ").append(Fmt.dateTime(s.startedAt)).append('\n');
        if (s.closed) sb.append("النهاية: ").append(Fmt.dateTime(s.endedAt)).append('\n');
        sb.append('\n');
        sb.append("عدد الطلبات: ").append(orders).append('\n');
        sb.append("الطلبات الملغاة: ").append(cancelled).append('\n');
        sb.append("إجمالي المبيعات: ").append(Fmt.money(sales)).append('\n');
        sb.append("مجموع الخصومات: ").append(Fmt.money(disc)).append('\n');
        sb.append("مجموع أجور التوصيل: ").append(Fmt.money(deliv)).append('\n');
        sb.append('\n');
        sb.append("افتتاح الصندوق: ").append(Fmt.money(s.openingCash)).append('\n');
        sb.append("المتوقع في الصندوق: ").append(Fmt.money(expected)).append('\n');
        if (s.closed) {
            long diff = s.closingCash - expected;
            sb.append("الفعلي عند الإغلاق: ").append(Fmt.money(s.closingCash)).append('\n');
            sb.append("الفرق: ").append(diff > 0 ? "+" : "").append(Fmt.money(diff)).append('\n');
            if (s.closingNote != null && !s.closingNote.isEmpty()) {
                sb.append("ملاحظة: ").append(s.closingNote).append('\n');
            }
        }
        List<TopItem> top = rr.topItemsForShift(shiftId, 10);
        if (!top.isEmpty()) {
            sb.append("\nأكثر الأصناف مبيعاً:\n");
            for (TopItem t : top) {
                sb.append("• ").append(t.name).append("  ×").append(t.qty)
                        .append("  =  ").append(Fmt.money(t.revenue)).append('\n');
            }
        }

        TextView tv = new TextView(a);
        tv.setText(sb.toString());
        tv.setTextSize(16);
        tv.setLineSpacing(0, 1.2f);
        tv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        int p = Ui.dp(a, 20);
        tv.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(a);
        sv.addView(tv);

        AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("تقرير الوردية " + Fmt.date(s.startedAt))
                .setView(sv)
                .setPositiveButton("🖨 طباعة التقرير", (x, w) -> print(a, s))
                .setNegativeButton("إغلاق", null)
                .setNeutralButton("تصدير طلبات الوردية CSV", (x, w) -> export(a, shiftId))
                .create();
        Ui.showRtl(d);
    }

    /** يطبع تقرير الوردية (الأصناف حسب الأقسام، السائقون، والملخص). */
    public static void print(final Activity a, final Shift s) {
        Ui.toast(a, "جارٍ طباعة تقرير الوردية " + Fmt.date(s.startedAt) + "...");
        new DailySalesReportPrinter(a).printShift(s, (ok, msg) -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            Ui.toast(a, msg);
        });
    }

    private static void export(final Activity a, final long shiftId) {
        new Thread(() -> {
            try {
                final File f = CsvExporter.exportShiftOrders(a, shiftId, "shift_" + shiftId);
                a.runOnUiThread(() -> a.startActivity(CsvExporter.shareIntent(a, f)));
            } catch (Exception e) {
                a.runOnUiThread(() -> Ui.toast(a, "تعذر التصدير: " + e.getMessage()));
            }
        }, "csv").start();
    }
}

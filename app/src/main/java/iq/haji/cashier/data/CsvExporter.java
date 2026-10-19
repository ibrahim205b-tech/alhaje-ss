package iq.haji.cashier.data;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import iq.haji.cashier.model.Order;
import iq.haji.cashier.model.OrderLine;
import iq.haji.cashier.model.OrderStatus;
import iq.haji.cashier.model.OrderType;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.util.Fmt;

/** تصدير CSV بترميز UTF-8 مع BOM ليفتح العربي صحيحاً في Excel. */
public final class CsvExporter {
    private CsvExporter() {}

    private static File exportsDir(Context ctx) throws IOException {
        File d = new File(ctx.getCacheDir(), "exports");
        if (!d.exists() && !d.mkdirs()) throw new IOException("تعذر إنشاء مجلد التصدير");
        return d;
    }

    private static String cell(String s) {
        if (s == null) s = "";
        // حماية من حقن الصيغ في Excel
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static void row(BufferedWriter w, String... cells) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cells[i]);
        }
        sb.append("\r\n");
        w.write(sb.toString());
    }

    private static BufferedWriter open(File f) throws IOException {
        BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8));
        w.write('\uFEFF');
        return w;
    }

    /** طلبات فترة زمنية (from شامل، toExclusive غير شامل). */
    public static File exportOrders(Context ctx, long from, long toExclusive, String fileName) throws IOException {
        OrderRepository repo = new OrderRepository(ctx);
        List<Order> orders = repo.listOrders(0, null, from, toExclusive, null, 1_000_000);
        File f = new File(exportsDir(ctx), safe(fileName) + ".csv");
        try (BufferedWriter w = open(f)) {
            writeOrderHeader(w);
            // من الأقدم إلى الأحدث
            for (int i = orders.size() - 1; i >= 0; i--) {
                writeOrderRow(w, repo.getOrder(orders.get(i).id));
            }
        }
        return f;
    }

    public static File exportShiftOrders(Context ctx, long shiftId, String fileName) throws IOException {
        OrderRepository repo = new OrderRepository(ctx);
        List<Order> orders = repo.listOrders(shiftId, null, 0, 0, null, 1_000_000);
        File f = new File(exportsDir(ctx), safe(fileName) + ".csv");
        try (BufferedWriter w = open(f)) {
            writeOrderHeader(w);
            for (int i = orders.size() - 1; i >= 0; i--) {
                writeOrderRow(w, repo.getOrder(orders.get(i).id));
            }
        }
        return f;
    }

    private static void writeOrderHeader(BufferedWriter w) throws IOException {
        row(w, cell("رقم الطلب"), cell("التاريخ"), cell("الوقت"), cell("رقم الوردية"), cell("النوع"),
                cell("الحالة"), cell("الزبون"), cell("الهاتف"), cell("العنوان"), cell("السائق"),
                cell("الأصناف"), cell("المجموع الجزئي"), cell("الخصم"), cell("أجور التوصيل"),
                cell("المجموع النهائي"), cell("طريقة الدفع"), cell("الموظف"), cell("ملاحظات"));
    }

    private static void writeOrderRow(BufferedWriter w, Order o) throws IOException {
        if (o == null) return;
        StringBuilder items = new StringBuilder();
        for (OrderLine l : o.lines) {
            if (items.length() > 0) items.append(" ; ");
            items.append(l.name).append(" x").append(l.qty);
        }
        row(w,
                String.valueOf(o.id),
                cell(Fmt.date(o.createdAt)),
                cell(Fmt.time(o.createdAt)),
                String.valueOf(o.shiftId),
                cell(OrderType.label(o.type)),
                cell(OrderStatus.label(o.status)),
                cell(o.customerName),
                cell(o.phone),
                cell(o.address),
                cell(o.driver),
                cell(items.toString()),
                String.valueOf(o.subtotal),
                String.valueOf(o.discount),
                String.valueOf(o.deliveryFee),
                String.valueOf(o.total),
                cell(o.paymentMethod),
                cell(o.cashierName),
                cell(o.notes));
    }

    public static File exportShifts(Context ctx, String fileName) throws IOException {
        List<Shift> shifts = new ShiftRepository(ctx).listShifts(1_000_000);
        File f = new File(exportsDir(ctx), safe(fileName) + ".csv");
        try (BufferedWriter w = open(f)) {
            row(w, cell("رقم الوردية"), cell("الموظف"), cell("البداية"), cell("النهاية"), cell("الحالة"),
                    cell("افتتاح الصندوق"), cell("إغلاق الصندوق"), cell("عدد الطلبات"), cell("الملغاة"),
                    cell("إجمالي المبيعات"), cell("الخصومات"), cell("أجور التوصيل"), cell("ملاحظة الإغلاق"));
            for (Shift s : shifts) {
                row(w,
                        String.valueOf(s.id),
                        cell(s.cashierName),
                        cell(Fmt.dateTime(s.startedAt)),
                        cell(s.closed ? Fmt.dateTime(s.endedAt) : ""),
                        cell(s.closed ? "مغلقة" : "مفتوحة"),
                        String.valueOf(s.openingCash),
                        String.valueOf(s.closingCash),
                        String.valueOf(s.ordersCount),
                        String.valueOf(s.cancelledCount),
                        String.valueOf(s.salesTotal),
                        String.valueOf(s.discountsTotal),
                        String.valueOf(s.deliveryTotal),
                        cell(s.closingNote));
            }
        }
        return f;
    }

    private static String safe(String name) {
        String n = name == null ? "export" : name.replaceAll("[^A-Za-z0-9_\\-]", "_");
        return n.isEmpty() ? "export" : n;
    }

    /** نافذة مشاركة/حفظ الملف عبر FileProvider. */
    public static Intent shareIntent(Context ctx, File file) {
        Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", file);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return Intent.createChooser(i, "تصدير التقرير");
    }
}

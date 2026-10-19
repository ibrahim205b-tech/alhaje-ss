package iq.haji.cashier.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import iq.haji.cashier.model.OrderStatus;
import iq.haji.cashier.model.SalesSummary;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.util.AppException;
import iq.haji.cashier.util.Fmt;

/** الورديات: كل وردية مستقلة، وتقرير الإغلاق يُحفظ ثابتاً ولا يُصفَّر شيء من الطلبات. */
public class ShiftRepository {
    private static final String COLS = "id,started_at,ended_at,cashier_name,opening_cash,closing_cash,"
            + "closing_note,closed,orders_count,cancelled_count,sales_total,discounts_total,delivery_total";

    private final Context ctx;

    public ShiftRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private SQLiteDatabase db() {
        return DatabaseHelper.get(ctx).getWritableDatabase();
    }

    private Shift read(Cursor c) {
        Shift s = new Shift();
        s.id = c.getLong(0);
        s.startedAt = c.getLong(1);
        s.endedAt = c.getLong(2);
        s.cashierName = c.getString(3);
        s.openingCash = c.getLong(4);
        s.closingCash = c.getLong(5);
        s.closingNote = c.getString(6);
        s.closed = c.getInt(7) == 1;
        s.ordersCount = c.getInt(8);
        s.cancelledCount = c.getInt(9);
        s.salesTotal = c.getLong(10);
        s.discountsTotal = c.getLong(11);
        s.deliveryTotal = c.getLong(12);
        return s;
    }

    /** الوردية المفتوحة حالياً أو null. */
    public Shift getOpenShift() {
        try (Cursor c = db().rawQuery("SELECT " + COLS + " FROM shifts WHERE closed=0 ORDER BY id DESC LIMIT 1", null)) {
            if (c.moveToFirst()) return read(c);
        }
        return null;
    }

    public Shift getShift(long id) {
        try (Cursor c = db().rawQuery("SELECT " + COLS + " FROM shifts WHERE id=?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) return read(c);
        }
        return null;
    }

    public List<Shift> listShifts(int limit) {
        List<Shift> out = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT " + COLS + " FROM shifts ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) out.add(read(c));
        }
        return out;
    }

    public long requireOpenShiftId() {
        Shift s = getOpenShift();
        if (s == null) throw new AppException("لا توجد وردية مفتوحة. ابدأ وردية من شاشة الورديات أولاً.");
        return s.id;
    }

    public Shift startShift(String cashierName, long openingCash) {
        if (getOpenShift() != null) throw new AppException("توجد وردية مفتوحة بالفعل. أغلقها قبل بدء وردية جديدة.");
        if (openingCash < 0) throw new AppException("مبلغ افتتاح الصندوق غير صحيح");
        String name = cashierName == null ? "" : cashierName.trim();
        if (name.isEmpty()) name = new AppSettings(ctx).get(AppSettings.CASHIER_NAME, "الكاشير");

        ContentValues cv = new ContentValues();
        cv.put("started_at", System.currentTimeMillis());
        cv.put("cashier_name", name);
        cv.put("opening_cash", openingCash);
        long id = db().insert("shifts", null, cv);
        if (id < 0) throw new AppException("تعذر بدء الوردية");
        AuditLog.log(ctx, "SHIFT_START", "#" + id + " " + name + " افتتاح " + Fmt.number(openingCash));
        return getShift(id);
    }

    public SalesSummary currentSummary(long shiftId) {
        return new ReportRepository(ctx).summaryForShift(shiftId);
    }

    /** عدد الطلبات المحفوظة بحالة بانتظار الطباعة داخل وردية. */
    public int pendingPrintCount(long shiftId) {
        try (Cursor c = db().rawQuery("SELECT COUNT(*) FROM orders WHERE shift_id=? AND status IN (?,?)",
                new String[]{String.valueOf(shiftId), OrderStatus.NEW, OrderStatus.PENDING_PRINT})) {
            if (c.moveToFirst()) return c.getInt(0);
        }
        return 0;
    }

    /**
     * يغلق الوردية ويحفظ تقريرها. يرفض الإغلاق إذا بقيت طلبات بانتظار الطباعة حتى لا تضيع من المبيعات.
     * لا يُحذف ولا يُصفَّر أي طلب.
     */
    public Shift closeShift(long shiftId, long closingCash, String note) {
        if (closingCash < 0) throw new AppException("مبلغ الصندوق عند الإغلاق غير صحيح");
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            Shift s = getShift(shiftId);
            if (s == null) throw new AppException("الوردية غير موجودة");
            if (s.closed) throw new AppException("هذه الوردية مغلقة بالفعل");

            int pending = pendingPrintCount(shiftId);
            if (pending > 0) {
                throw new AppException("يوجد " + pending
                        + " طلب بانتظار الطباعة. أعد طباعته أو ألغه قبل إغلاق الوردية.");
            }
            SalesSummary sum = currentSummary(shiftId);

            ContentValues cv = new ContentValues();
            cv.put("ended_at", System.currentTimeMillis());
            cv.put("closing_cash", closingCash);
            cv.put("closing_note", note == null ? "" : note.trim());
            cv.put("closed", 1);
            cv.put("orders_count", sum.ordersCount);
            cv.put("cancelled_count", sum.cancelledCount);
            cv.put("sales_total", sum.salesTotal);
            cv.put("discounts_total", sum.discountsTotal);
            cv.put("delivery_total", sum.deliveryTotal);
            int rows = db.update("shifts", cv, "id=? AND closed=0", new String[]{String.valueOf(shiftId)});
            if (rows != 1) throw new AppException("تعذر إغلاق الوردية");

            AuditLog.log(db, "SHIFT_CLOSE", "#" + shiftId + " مبيعات " + Fmt.number(sum.salesTotal)
                    + " طلبات " + sum.ordersCount);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return getShift(shiftId);
    }
}

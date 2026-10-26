package iq.haji.cashier.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import iq.haji.cashier.model.Order;
import iq.haji.cashier.model.OrderLine;
import iq.haji.cashier.model.OrderStatus;
import iq.haji.cashier.model.OrderType;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.model.StatusLogEntry;
import iq.haji.cashier.util.AppException;
import iq.haji.cashier.util.Fmt;

/**
 * منطق الطلبات وحدها (بدون أي كود طباعة). الطباعة في ReceiptPrinter وتستدعي markPrinted / recordPrintFailure.
 */
public class OrderRepository {
    private static final String DRAFT_KEY = "draft_order";
    private static final String ORDER_COLS = "id,uuid,created_at,shift_id,type,status,customer_name,phone,"
            + "address,driver,notes,payment_method,delivery_fee,discount,subtotal,total,print_count,"
            + "printed_at,cashier_name,whatsapp";

    private final Context ctx;
    private final AppSettings settings;

    public OrderRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.settings = new AppSettings(ctx);
    }

    private SQLiteDatabase db() {
        return DatabaseHelper.get(ctx).getWritableDatabase();
    }

    // ---------------- رقم الطلب القادم ----------------

    /** للعرض فقط: الرقم الذي سيُمنح عند الحفظ. */
    public long nextOrderNumber() {
        try (Cursor c = db().rawQuery("SELECT seq FROM sqlite_sequence WHERE name='orders'", null)) {
            if (c.moveToFirst()) return c.getLong(0) + 1;
        }
        return 1;
    }

    // ---------------- المسودة (الطلب الجاري) ----------------

    public void saveDraft(Order o) {
        if (o == null || o.lines.isEmpty()) {
            clearDraft();
            return;
        }
        try {
            JSONObject j = new JSONObject();
            j.put("type", o.type);
            j.put("customerName", o.customerName == null ? "" : o.customerName);
            j.put("phone", o.phone == null ? "" : o.phone);
            j.put("whatsapp", o.whatsapp == null ? "" : o.whatsapp);
            j.put("address", o.address == null ? "" : o.address);
            j.put("driver", o.driver == null ? "" : o.driver);
            j.put("notes", o.notes == null ? "" : o.notes);
            j.put("discount", o.discount);
            j.put("deliveryFee", o.deliveryFee);
            j.put("createdAt", o.createdAt);
            JSONArray arr = new JSONArray();
            for (OrderLine l : o.lines) {
                JSONObject lj = new JSONObject();
                lj.put("productId", l.productId);
                lj.put("name", l.name);
                lj.put("unitPrice", l.unitPrice);
                lj.put("qty", l.qty);
                lj.put("note", l.note == null ? "" : l.note);
                arr.put(lj);
            }
            j.put("lines", arr);
            settings.set(DRAFT_KEY, j.toString());
        } catch (JSONException e) {
            // فشل الحفظ التلقائي للمسودة لا يوقف العمل
        }
    }

    public Order loadDraft() {
        String s = settings.get(DRAFT_KEY, null);
        if (s == null || s.isEmpty()) return null;
        try {
            JSONObject j = new JSONObject(s);
            Order o = new Order();
            o.type = j.optString("type", OrderType.TAKEAWAY);
            o.customerName = j.optString("customerName", "");
            o.phone = j.optString("phone", "");
            o.whatsapp = j.optString("whatsapp", "");
            o.address = j.optString("address", "");
            o.driver = j.optString("driver", "");
            o.notes = j.optString("notes", "");
            o.discount = j.optLong("discount", 0);
            o.deliveryFee = j.optLong("deliveryFee", 0);
            o.createdAt = j.optLong("createdAt", System.currentTimeMillis());
            JSONArray arr = j.optJSONArray("lines");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject lj = arr.getJSONObject(i);
                    OrderLine l = new OrderLine();
                    l.productId = lj.optLong("productId");
                    l.name = lj.optString("name");
                    l.unitPrice = lj.optLong("unitPrice");
                    l.qty = lj.optInt("qty", 1);
                    l.note = lj.optString("note", "");
                    o.lines.add(l);
                }
            }
            o.recalc();
            return o.lines.isEmpty() ? null : o;
        } catch (JSONException e) {
            return null;
        }
    }

    public void clearDraft() {
        db().delete("settings", "key=?", new String[]{DRAFT_KEY});
    }

    // ---------------- التحقق والحفظ ----------------

    private void checkTypeEnabled(String type) {
        boolean ok;
        if (OrderType.DELIVERY.equals(type)) ok = settings.getBool(AppSettings.ENABLE_DELIVERY, true);
        else if (OrderType.DINE_IN.equals(type)) ok = settings.getBool(AppSettings.ENABLE_DINE_IN, true);
        else if (OrderType.TAKEAWAY.equals(type)) ok = settings.getBool(AppSettings.ENABLE_TAKEAWAY, true);
        else ok = false;
        if (!ok) throw new AppException("نوع الطلب «" + OrderType.label(type) + "» غير مفعّل في الإعدادات");
    }

    /** يتحقق من صحة الطلب قبل الحفظ والطباعة. يرمي AppException برسالة عربية. */
    public void validate(Order o) {
        if (o.lines.isEmpty()) throw new AppException("الطلب فارغ. أضف صنفاً واحداً على الأقل.");
        for (OrderLine l : o.lines) {
            if (l.qty <= 0) throw new AppException("كمية غير صحيحة للصنف: " + l.name);
            if (l.unitPrice < 0) throw new AppException("سعر غير صحيح للصنف: " + l.name);
        }
        checkTypeEnabled(o.type);
        if (o.discount > 0 && !settings.getBool(AppSettings.ALLOW_DISCOUNT, true)) {
            throw new AppException("الخصم غير مفعّل في الإعدادات");
        }
        o.recalc();
    }

    /**
     * الخطوة 1-2 من الطباعة: يتحقق ويحفظ الطلب بحالة «بانتظار الطباعة» داخل الوردية المفتوحة،
     * ويمسح المسودة، كل ذلك في معاملة واحدة. إذا كان الطلب محفوظاً مسبقاً (o.id != 0) يعيد رقمه
     * دون إنشاء فاتورة جديدة (حماية إعادة المحاولة).
     */
    public long placeOrderForPrint(Order o) {
        if (o.id != 0) return o.id;
        validate(o);

        SQLiteDatabase db = db();
        long newId;
        db.beginTransaction();
        try {
            long shiftId = new ShiftRepository(ctx).requireOpenShiftId();
            long now = System.currentTimeMillis();
            String uuid = o.uuid == null ? UUID.randomUUID().toString() : o.uuid;
            String cashier = o.cashierName;
            if (cashier == null || cashier.trim().isEmpty()) {
                Shift s = new ShiftRepository(ctx).getShift(shiftId);
                cashier = s != null && s.cashierName != null ? s.cashierName
                        : settings.get(AppSettings.CASHIER_NAME, "الكاشير");
            }

            ContentValues cv = new ContentValues();
            cv.put("uuid", uuid);
            cv.put("created_at", now);
            cv.put("shift_id", shiftId);
            cv.put("type", o.type);
            cv.put("status", OrderStatus.PENDING_PRINT);
            cv.put("customer_name", nz(o.customerName));
            cv.put("phone", nz(o.phone));
            cv.put("address", nz(o.address));
            cv.put("driver", nz(o.driver));
            cv.put("notes", nz(o.notes));
            cv.put("payment_method", o.paymentMethod == null ? "نقدي" : o.paymentMethod);
            cv.put("delivery_fee", o.deliveryFee);
            cv.put("discount", o.discount);
            cv.put("subtotal", o.subtotal);
            cv.put("total", o.total);
            cv.put("print_count", 0);
            cv.put("printed_at", 0);
            cv.put("cashier_name", cashier);
            cv.put("whatsapp", nz(o.whatsapp));
            newId = db.insert("orders", null, cv);
            if (newId < 0) throw new AppException("تعذر حفظ الطلب");

            for (OrderLine l : o.lines) {
                ContentValues lc = new ContentValues();
                lc.put("order_id", newId);
                lc.put("product_id", l.productId);
                lc.put("name", l.name);
                lc.put("unit_price", l.unitPrice);
                lc.put("qty", l.qty);
                lc.put("note", nz(l.note));
                if (db.insert("order_items", null, lc) < 0) throw new AppException("تعذر حفظ أصناف الطلب");
            }
            logStatus(db, newId, OrderStatus.PENDING_PRINT, "حُفظ الطلب بانتظار الطباعة");
            AuditLog.log(db, "ORDER_CREATED", "#" + newId + " " + OrderType.label(o.type)
                    + " " + Fmt.number(o.total));
            db.delete("settings", "key=?", new String[]{DRAFT_KEY});

            db.setTransactionSuccessful();

            o.id = newId;
            o.uuid = uuid;
            o.createdAt = now;
            o.shiftId = shiftId;
            o.cashierName = cashier;
            o.status = OrderStatus.PENDING_PRINT;
        } finally {
            db.endTransaction();
        }
        return newId;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private void logStatus(SQLiteDatabase db, long orderId, String status, String note) {
        ContentValues cv = new ContentValues();
        cv.put("order_id", orderId);
        cv.put("status", status);
        cv.put("changed_at", System.currentTimeMillis());
        cv.put("note", note);
        db.insert("order_status_log", null, cv);
    }

    // ---------------- القراءة ----------------

    private Order readOrder(Cursor c) {
        Order o = new Order();
        o.id = c.getLong(0);
        o.uuid = c.getString(1);
        o.createdAt = c.getLong(2);
        o.shiftId = c.getLong(3);
        o.type = c.getString(4);
        o.status = c.getString(5);
        o.customerName = c.getString(6);
        o.phone = c.getString(7);
        o.address = c.getString(8);
        o.driver = c.getString(9);
        o.notes = c.getString(10);
        o.paymentMethod = c.getString(11);
        o.deliveryFee = c.getLong(12);
        o.discount = c.getLong(13);
        o.subtotal = c.getLong(14);
        o.total = c.getLong(15);
        o.printCount = c.getInt(16);
        o.printedAt = c.getLong(17);
        o.cashierName = c.getString(18);
        o.whatsapp = c.isNull(19) ? "" : c.getString(19);
        return o;
    }

    public Order getOrder(long id) {
        SQLiteDatabase db = db();
        Order o = null;
        try (Cursor c = db.rawQuery("SELECT " + ORDER_COLS + " FROM orders WHERE id=?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) o = readOrder(c);
        }
        if (o == null) return null;
        try (Cursor c = db.rawQuery("SELECT id,product_id,name,unit_price,qty,note FROM order_items "
                + "WHERE order_id=? ORDER BY id", new String[]{String.valueOf(id)})) {
            while (c.moveToNext()) {
                OrderLine l = new OrderLine();
                l.id = c.getLong(0);
                l.orderId = id;
                l.productId = c.getLong(1);
                l.name = c.getString(2);
                l.unitPrice = c.getLong(3);
                l.qty = c.getInt(4);
                l.note = c.getString(5);
                o.lines.add(l);
            }
        }
        return o;
    }

    /**
     * قائمة الطلبات (بدون سطور الأصناف).
     *
     * @param shiftId     0 = كل الورديات
     * @param status      null = كل الحالات
     * @param from        0 = بدون حد أدنى
     * @param toExclusive 0 = بدون حد أعلى
     * @param query       رقم طلب أو جزء من اسم/هاتف الزبون (اختياري)
     */
    public List<Order> listOrders(long shiftId, String status, long from, long toExclusive,
                                  String query, int limit) {
        List<Order> out = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + ORDER_COLS + " FROM orders WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (shiftId > 0) {
            sql.append(" AND shift_id=?");
            args.add(String.valueOf(shiftId));
        }
        if (status != null) {
            sql.append(" AND status=?");
            args.add(status);
        }
        if (from > 0) {
            sql.append(" AND created_at>=?");
            args.add(String.valueOf(from));
        }
        if (toExclusive > 0) {
            sql.append(" AND created_at<?");
            args.add(String.valueOf(toExclusive));
        }
        String q = query == null ? "" : Fmt.normalizeDigits(query);
        if (!q.isEmpty()) {
            sql.append(" AND (CAST(id AS TEXT)=? OR phone LIKE ? OR customer_name LIKE ?)");
            args.add(q);
            args.add("%" + q + "%");
            args.add("%" + q + "%");
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(String.valueOf(limit));
        try (Cursor c = db().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) out.add(readOrder(c));
        }
        return out;
    }

    public List<Order> listPendingPrint() {
        return listOrders(0, OrderStatus.PENDING_PRINT, 0, 0, null, 200);
    }

    public List<StatusLogEntry> getStatusLog(long orderId) {
        List<StatusLogEntry> out = new ArrayList<>();
        try (Cursor c = db().rawQuery("SELECT status,changed_at,note FROM order_status_log "
                + "WHERE order_id=? ORDER BY id", new String[]{String.valueOf(orderId)})) {
            while (c.moveToNext()) {
                StatusLogEntry e = new StatusLogEntry();
                e.status = c.getString(0);
                e.changedAt = c.getLong(1);
                e.note = c.getString(2);
                out.add(e);
            }
        }
        return out;
    }

    // ---------------- نتائج الطباعة ----------------

    /**
     * يُستدعى عند نجاح إرسال الفاتورة للطابعة: ينقل «بانتظار الطباعة» إلى «قيد التجهيز»،
     * أو يسجل إعادة طباعة فقط إذا كان الطلب قد طُبع سابقاً. لا ينشئ أي طلب جديد.
     */
    public void markPrinted(long orderId) {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            Order o = getOrder(orderId);
            if (o == null) throw new AppException("الطلب غير موجود");
            long now = System.currentTimeMillis();
            boolean first = OrderStatus.PENDING_PRINT.equals(o.status) || OrderStatus.NEW.equals(o.status);

            ContentValues cv = new ContentValues();
            cv.put("print_count", o.printCount + 1);
            cv.put("printed_at", now);
            if (first) cv.put("status", OrderStatus.PREPARING);
            db.update("orders", cv, "id=?", new String[]{String.valueOf(orderId)});

            if (first) {
                logStatus(db, orderId, OrderStatus.PREPARING, "أُرسلت الفاتورة للطابعة بنجاح");
            } else {
                logStatus(db, orderId, o.status, "إعادة طباعة الفاتورة");
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** يسجل فشل الطباعة في سجل الطلب دون تغيير حالته أو محتوياته. */
    public void recordPrintFailure(long orderId, String reason) {
        Order o = getOrder(orderId);
        if (o == null) return;
        logStatus(db(), orderId, o.status, "فشلت الطباعة: " + (reason == null ? "" : reason));
    }

    // ---------------- تغيير الحالة والإلغاء ----------------

    private static boolean allowed(String from, String to) {
        if (from == null || from.equals(to)) return false;
        if (OrderStatus.PENDING_PRINT.equals(from)) return OrderStatus.CANCELLED.equals(to);
        if (OrderStatus.NEW.equals(from)) return OrderStatus.CANCELLED.equals(to);
        if (OrderStatus.PREPARING.equals(from)) {
            return OrderStatus.READY.equals(to) || OrderStatus.COMPLETED.equals(to)
                    || OrderStatus.CANCELLED.equals(to);
        }
        if (OrderStatus.READY.equals(from)) {
            return OrderStatus.COMPLETED.equals(to) || OrderStatus.CANCELLED.equals(to);
        }
        return false; // مكتمل وملغى حالتان نهائيتان
    }

    public static boolean canChange(String from, String to) {
        return allowed(from, to);
    }

    /** «بانتظار الطباعة → قيد التجهيز» لا تتم يدوياً بل فقط بنجاح الطباعة. */
    public void setStatus(long orderId, String newStatus, String note) {
        SQLiteDatabase db = db();
        db.beginTransaction();
        try {
            Order o = getOrder(orderId);
            if (o == null) throw new AppException("الطلب غير موجود");
            Shift s = new ShiftRepository(ctx).getShift(o.shiftId);
            if (s != null && s.closed) throw new AppException("الوردية مغلقة، لا يمكن تعديل طلباتها");
            if (!allowed(o.status, newStatus)) {
                throw new AppException("لا يمكن تغيير الحالة من «" + OrderStatus.label(o.status)
                        + "» إلى «" + OrderStatus.label(newStatus) + "»");
            }
            ContentValues cv = new ContentValues();
            cv.put("status", newStatus);
            db.update("orders", cv, "id=?", new String[]{String.valueOf(orderId)});
            logStatus(db, orderId, newStatus, note == null || note.trim().isEmpty() ? null : note.trim());
            if (OrderStatus.CANCELLED.equals(newStatus)) {
                AuditLog.log(db, "ORDER_CANCELLED", "#" + orderId + " " + Fmt.number(o.total)
                        + (note == null ? "" : " سبب: " + note.trim()));
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void cancelOrder(long orderId, String reason) {
        setStatus(orderId, OrderStatus.CANCELLED, reason);
    }

    public void updateDriver(long orderId, String driver) {
        Order o = getOrder(orderId);
        if (o == null) throw new AppException("الطلب غير موجود");
        Shift s = new ShiftRepository(ctx).getShift(o.shiftId);
        if (s != null && s.closed) throw new AppException("الوردية مغلقة، لا يمكن تعديل طلباتها");
        ContentValues cv = new ContentValues();
        cv.put("driver", nz(driver));
        SQLiteDatabase db = db();
        db.update("orders", cv, "id=?", new String[]{String.valueOf(orderId)});
        logStatus(db, orderId, o.status, "السائق: " + nz(driver));
    }
}

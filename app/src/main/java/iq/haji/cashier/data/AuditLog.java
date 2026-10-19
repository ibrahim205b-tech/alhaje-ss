package iq.haji.cashier.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import iq.haji.cashier.util.Fmt;

/** سجل العمليات المهمة: إلغاء الطلبات، تعديل الأسعار، حذف الأصناف، الاستعادة، إغلاق الوردية. */
public final class AuditLog {
    private AuditLog() {}

    public static void log(Context ctx, String action, String details) {
        log(DatabaseHelper.get(ctx).getWritableDatabase(), action, details);
    }

    public static void log(SQLiteDatabase db, String action, String details) {
        ContentValues cv = new ContentValues();
        cv.put("at", System.currentTimeMillis());
        cv.put("action", action);
        cv.put("details", details);
        db.insert("audit_log", null, cv);
    }

    public static List<String> recent(Context ctx, int limit) {
        List<String> out = new ArrayList<>();
        SQLiteDatabase db = DatabaseHelper.get(ctx).getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT at,action,details FROM audit_log ORDER BY id DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            while (c.moveToNext()) {
                out.add(Fmt.dateTime(c.getLong(0)) + "  |  " + c.getString(1)
                        + (c.isNull(2) ? "" : "  |  " + c.getString(2)));
            }
        }
        return out;
    }
}

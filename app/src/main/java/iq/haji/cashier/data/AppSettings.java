package iq.haji.cashier.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * إعدادات التطبيق والطابعة وترويسة الفاتورة. محفوظة في جدول settings داخل نفس قاعدة البيانات
 * فتدخل تلقائياً في النسخ الاحتياطي والاستعادة.
 */
public class AppSettings {

    // ترويسة الفاتورة
    public static final String RESTAURANT_NAME = "restaurant_name";
    public static final String RESTAURANT_ADDRESS = "restaurant_address";
    public static final String RESTAURANT_PHONE = "restaurant_phone";
    public static final String RECEIPT_FOOTER = "receipt_footer";

    // الطلبات
    public static final String ENABLE_DINE_IN = "enable_dine_in";
    public static final String ENABLE_TAKEAWAY = "enable_takeaway";
    public static final String ENABLE_DELIVERY = "enable_delivery";
    public static final String DEFAULT_DELIVERY_FEE = "default_delivery_fee";
    public static final String ALLOW_DISCOUNT = "allow_discount";
    public static final String CASHIER_NAME = "cashier_name";

    // الطابعة
    public static final String PRINTER_CONNECTION = "printer_connection"; // BLUETOOTH | NETWORK
    public static final String PRINTER_BT_ADDRESS = "printer_bt_address";
    public static final String PRINTER_BT_NAME = "printer_bt_name";
    public static final String PRINTER_IP = "printer_ip";
    public static final String PRINTER_PORT = "printer_port";
    public static final String PAPER_WIDTH_MM = "paper_width_mm"; // 58 | 80
    public static final String PRINT_FONT_SCALE = "print_font_scale"; // 1 | 2
    public static final String PRINT_MARGIN_DOTS = "print_margin_dots";
    public static final String PRINT_ALIGN = "print_align"; // RIGHT | CENTER | LEFT
    public static final String PRINT_COPIES = "print_copies";
    public static final String PRINT_CUT = "print_cut";
    public static final String SETUP_DONE = "setup_done";

    private final Context context;

    public AppSettings(Context context) {
        this.context = context.getApplicationContext();
    }

    private SQLiteDatabase db() {
        return DatabaseHelper.get(context).getWritableDatabase();
    }

    public String get(String key, String def) {
        try (Cursor c = db().rawQuery("SELECT value FROM settings WHERE key=?", new String[]{key})) {
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        }
        return def;
    }

    public void set(String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put("key", key);
        cv.put("value", value);
        db().insertWithOnConflict("settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean getBool(String key, boolean def) {
        String v = get(key, null);
        if (v == null) return def;
        return "1".equals(v);
    }

    public void setBool(String key, boolean value) {
        set(key, value ? "1" : "0");
    }

    public int getInt(String key, int def) {
        String v = get(key, null);
        if (v == null) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void setInt(String key, int value) {
        set(key, String.valueOf(value));
    }

    public long getLong(String key, long def) {
        String v = get(key, null);
        if (v == null) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public void setLong(String key, long value) {
        set(key, String.valueOf(value));
    }

    // اختصارات بقيم افتراضية معقولة

    public String restaurantName() {
        return get(RESTAURANT_NAME, "مطعم الحجي");
    }

    public int paperWidthMm() {
        int w = getInt(PAPER_WIDTH_MM, 80);
        return w == 58 ? 58 : 80;
    }

    public String connectionType() {
        return get(PRINTER_CONNECTION, "BLUETOOTH");
    }

    public int printerPort() {
        return getInt(PRINTER_PORT, 9100);
    }

    public int copies() {
        int c = getInt(PRINT_COPIES, 1);
        return Math.max(1, Math.min(c, 5));
    }
}

package iq.haji.cashier.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * قاعدة SQLite الرئيسية. كل الطلبات والورديات والأصناف والإعدادات تُحفظ هنا.
 * المبالغ أعداد صحيحة (دينار). أرقام الطلبات AUTOINCREMENT فلا يُعاد استعمال رقم حتى بعد الحذف أو إعادة التشغيل.
 */
public class DatabaseHelper extends SQLiteOpenHelper {

    public static final String DB_NAME = "alhaje_ss.db";
    public static final int DB_VERSION = 1;

    private static DatabaseHelper instance;

    public static synchronized DatabaseHelper get(Context context) {
        if (instance == null) {
            instance = new DatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    /** يُستعمل بعد استعادة نسخة احتياطية لإعادة فتح الملف الجديد. */
    public static synchronized void reset() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    private DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE categories ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL,"
                + "sort_order INTEGER NOT NULL DEFAULT 0,"
                + "active INTEGER NOT NULL DEFAULT 1)");

        db.execSQL("CREATE TABLE products ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "category_id INTEGER NOT NULL,"
                + "name TEXT NOT NULL,"
                + "price INTEGER NOT NULL CHECK(price >= 0),"
                + "image_uri TEXT,"
                + "active INTEGER NOT NULL DEFAULT 1,"
                + "FOREIGN KEY(category_id) REFERENCES categories(id))");
        db.execSQL("CREATE INDEX idx_products_cat ON products(category_id)");
        db.execSQL("CREATE INDEX idx_products_name ON products(name)");

        db.execSQL("CREATE TABLE shifts ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "started_at INTEGER NOT NULL,"
                + "ended_at INTEGER NOT NULL DEFAULT 0,"
                + "cashier_name TEXT,"
                + "opening_cash INTEGER NOT NULL DEFAULT 0,"
                + "closing_cash INTEGER NOT NULL DEFAULT 0,"
                + "closing_note TEXT,"
                + "closed INTEGER NOT NULL DEFAULT 0,"
                + "orders_count INTEGER NOT NULL DEFAULT 0,"
                + "cancelled_count INTEGER NOT NULL DEFAULT 0,"
                + "sales_total INTEGER NOT NULL DEFAULT 0,"
                + "discounts_total INTEGER NOT NULL DEFAULT 0,"
                + "delivery_total INTEGER NOT NULL DEFAULT 0)");

        // id هنا هو رقم الطلب المتسلسل.
        db.execSQL("CREATE TABLE orders ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "uuid TEXT NOT NULL UNIQUE,"
                + "created_at INTEGER NOT NULL,"
                + "shift_id INTEGER NOT NULL,"
                + "type TEXT NOT NULL,"
                + "status TEXT NOT NULL,"
                + "customer_name TEXT,"
                + "phone TEXT,"
                + "address TEXT,"
                + "driver TEXT,"
                + "notes TEXT,"
                + "payment_method TEXT,"
                + "delivery_fee INTEGER NOT NULL DEFAULT 0,"
                + "discount INTEGER NOT NULL DEFAULT 0,"
                + "subtotal INTEGER NOT NULL DEFAULT 0,"
                + "total INTEGER NOT NULL DEFAULT 0,"
                + "print_count INTEGER NOT NULL DEFAULT 0,"
                + "printed_at INTEGER NOT NULL DEFAULT 0,"
                + "cashier_name TEXT,"
                + "FOREIGN KEY(shift_id) REFERENCES shifts(id))");
        db.execSQL("CREATE INDEX idx_orders_shift ON orders(shift_id)");
        db.execSQL("CREATE INDEX idx_orders_created ON orders(created_at)");
        db.execSQL("CREATE INDEX idx_orders_status ON orders(status)");

        db.execSQL("CREATE TABLE order_items ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "order_id INTEGER NOT NULL,"
                + "product_id INTEGER NOT NULL,"
                + "name TEXT NOT NULL,"
                + "unit_price INTEGER NOT NULL,"
                + "qty INTEGER NOT NULL CHECK(qty > 0),"
                + "note TEXT,"
                + "FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_items_order ON order_items(order_id)");

        db.execSQL("CREATE TABLE order_status_log ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "order_id INTEGER NOT NULL,"
                + "status TEXT NOT NULL,"
                + "changed_at INTEGER NOT NULL,"
                + "note TEXT,"
                + "FOREIGN KEY(order_id) REFERENCES orders(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX idx_log_order ON order_status_log(order_id)");

        db.execSQL("CREATE TABLE audit_log ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "at INTEGER NOT NULL,"
                + "action TEXT NOT NULL,"
                + "details TEXT)");

        db.execSQL("CREATE TABLE settings ("
                + "key TEXT PRIMARY KEY,"
                + "value TEXT)");

        seedMenu(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // الإصدار 1 فقط حالياً. أي ترقية مستقبلية تُضاف هنا بدون مسح البيانات.
    }

    /** بيانات أولية قابلة للتعديل والحذف بالكامل من شاشة إدارة الأصناف. */
    private void seedMenu(SQLiteDatabase db) {
        long meals = addCategory(db, "الوجبات", 1);
        long grills = addCategory(db, "المشويات", 2);
        long starters = addCategory(db, "المقبلات", 3);
        long drinks = addCategory(db, "المشروبات", 4);

        addProduct(db, meals, "وجبة بيتزا وسط", 8000);
        addProduct(db, meals, "وجبة بيتزا كبير", 12000);
        addProduct(db, meals, "وجبة برغر دجاج", 5000);
        addProduct(db, meals, "وجبة برغر لحم", 6000);
        addProduct(db, meals, "وجبة شاورما عربي", 4000);

        addProduct(db, grills, "كباب نصف كيلو", 12000);
        addProduct(db, grills, "تكة لحم", 13000);
        addProduct(db, grills, "شيش طاووق", 9000);
        addProduct(db, grills, "ربع دجاج مشوي", 6000);

        addProduct(db, starters, "حمص", 2500);
        addProduct(db, starters, "متبل", 2500);
        addProduct(db, starters, "سلطة", 2000);
        addProduct(db, starters, "بطاطا مقلية", 2000);

        addProduct(db, drinks, "ماء", 500);
        addProduct(db, drinks, "بيبسي", 1000);
        addProduct(db, drinks, "عيران", 1000);
        addProduct(db, drinks, "عصير طبيعي", 2500);
    }

    private long addCategory(SQLiteDatabase db, String name, int order) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("sort_order", order);
        cv.put("active", 1);
        return db.insert("categories", null, cv);
    }

    private void addProduct(SQLiteDatabase db, long categoryId, String name, long price) {
        ContentValues cv = new ContentValues();
        cv.put("category_id", categoryId);
        cv.put("name", name);
        cv.put("price", price);
        cv.put("active", 1);
        db.insert("products", null, cv);
    }
}

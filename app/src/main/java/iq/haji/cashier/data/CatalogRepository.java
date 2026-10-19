package iq.haji.cashier.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import iq.haji.cashier.model.Category;
import iq.haji.cashier.model.Product;
import iq.haji.cashier.util.AppException;
import iq.haji.cashier.util.Fmt;

/** إدارة الأقسام والأصناف. كل التعديلات تُحفظ فوراً في SQLite. */
public class CatalogRepository {
    public static final long MAX_PRICE = 100_000_000L;

    private final Context ctx;

    public CatalogRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private SQLiteDatabase db() {
        return DatabaseHelper.get(ctx).getWritableDatabase();
    }

    // ---------------- الأقسام ----------------

    public List<Category> getCategories(boolean onlyActive) {
        List<Category> out = new ArrayList<>();
        String sql = "SELECT id,name,sort_order,active FROM categories"
                + (onlyActive ? " WHERE active=1" : "") + " ORDER BY sort_order, id";
        try (Cursor c = db().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                out.add(new Category(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3) == 1));
            }
        }
        return out;
    }

    public Category getCategory(long id) {
        try (Cursor c = db().rawQuery("SELECT id,name,sort_order,active FROM categories WHERE id=?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) {
                return new Category(c.getLong(0), c.getString(1), c.getInt(2), c.getInt(3) == 1);
            }
        }
        return null;
    }

    private String cleanName(String name, String what) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) throw new AppException("أدخل " + what);
        if (n.length() > 60) throw new AppException(what + " طويل جداً (الحد 60 حرفاً)");
        return n;
    }

    public long addCategory(String name) {
        String n = cleanName(name, "اسم القسم");
        int next = 1;
        try (Cursor c = db().rawQuery("SELECT COALESCE(MAX(sort_order),0)+1 FROM categories", null)) {
            if (c.moveToFirst()) next = c.getInt(0);
        }
        ContentValues cv = new ContentValues();
        cv.put("name", n);
        cv.put("sort_order", next);
        cv.put("active", 1);
        long id = db().insert("categories", null, cv);
        if (id < 0) throw new AppException("تعذر حفظ القسم");
        AuditLog.log(ctx, "CATEGORY_ADD", n);
        return id;
    }

    public void renameCategory(long id, String name) {
        String n = cleanName(name, "اسم القسم");
        ContentValues cv = new ContentValues();
        cv.put("name", n);
        db().update("categories", cv, "id=?", new String[]{String.valueOf(id)});
        AuditLog.log(ctx, "CATEGORY_RENAME", "#" + id + " -> " + n);
    }

    public void setCategoryActive(long id, boolean active) {
        ContentValues cv = new ContentValues();
        cv.put("active", active ? 1 : 0);
        db().update("categories", cv, "id=?", new String[]{String.valueOf(id)});
        AuditLog.log(ctx, "CATEGORY_ACTIVE", "#" + id + " = " + active);
    }

    public void deleteCategory(long id) {
        try (Cursor c = db().rawQuery("SELECT COUNT(*) FROM products WHERE category_id=?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst() && c.getInt(0) > 0) {
                throw new AppException("لا يمكن حذف قسم يحتوي أصنافاً. انقل أصنافه أو أخفِ القسم بدلاً من حذفه.");
            }
        }
        db().delete("categories", "id=?", new String[]{String.valueOf(id)});
        AuditLog.log(ctx, "CATEGORY_DELETE", "#" + id);
    }

    // ---------------- الأصناف ----------------

    private static final String PRODUCT_COLS = "p.id,p.category_id,p.name,p.price,p.image_uri,p.active";

    private Product readProduct(Cursor c) {
        Product p = new Product();
        p.id = c.getLong(0);
        p.categoryId = c.getLong(1);
        p.name = c.getString(2);
        p.price = c.getLong(3);
        p.imageUri = c.isNull(4) ? null : c.getString(4);
        p.active = c.getInt(5) == 1;
        return p;
    }

    /**
     * @param categoryId 0 = كل الأقسام
     * @param query      نص بحث فوري (اختياري)
     * @param onlyActive true لشاشة الكاشير (صنف مفعّل وقسمه مفعّل)، false لشاشة الإدارة
     */
    public List<Product> getProducts(long categoryId, String query, boolean onlyActive) {
        List<Product> out = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + PRODUCT_COLS
                + " FROM products p JOIN categories c ON c.id=p.category_id WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (onlyActive) sql.append(" AND p.active=1 AND c.active=1");
        if (categoryId > 0) {
            sql.append(" AND p.category_id=?");
            args.add(String.valueOf(categoryId));
        }
        String q = query == null ? "" : query.trim();
        if (!q.isEmpty()) {
            sql.append(" AND p.name LIKE ?");
            args.add("%" + q + "%");
        }
        sql.append(" ORDER BY c.sort_order, p.name");
        try (Cursor c = db().rawQuery(sql.toString(), args.toArray(new String[0]))) {
            while (c.moveToNext()) out.add(readProduct(c));
        }
        return out;
    }

    public Product getProduct(long id) {
        try (Cursor c = db().rawQuery("SELECT " + PRODUCT_COLS + " FROM products p WHERE p.id=?",
                new String[]{String.valueOf(id)})) {
            if (c.moveToFirst()) return readProduct(c);
        }
        return null;
    }

    /** إضافة (id=0) أو تعديل. يتحقق من الاسم والسعر والقسم ويسجل تغيير السعر. */
    public long saveProduct(Product p) {
        String name = cleanName(p.name, "اسم الصنف");
        if (p.price <= 0) throw new AppException("السعر يجب أن يكون أكبر من صفر");
        if (p.price > MAX_PRICE) throw new AppException("السعر كبير جداً، تحقق من الأرقام");
        if (getCategory(p.categoryId) == null) throw new AppException("اختر قسماً صحيحاً");

        ContentValues cv = new ContentValues();
        cv.put("category_id", p.categoryId);
        cv.put("name", name);
        cv.put("price", p.price);
        if (p.imageUri == null || p.imageUri.isEmpty()) cv.putNull("image_uri");
        else cv.put("image_uri", p.imageUri);
        cv.put("active", p.active ? 1 : 0);

        if (p.id == 0) {
            long id = db().insert("products", null, cv);
            if (id < 0) throw new AppException("تعذر حفظ الصنف");
            AuditLog.log(ctx, "PRODUCT_ADD", name + " = " + Fmt.number(p.price));
            return id;
        }
        Product old = getProduct(p.id);
        int rows = db().update("products", cv, "id=?", new String[]{String.valueOf(p.id)});
        if (rows != 1) throw new AppException("الصنف غير موجود");
        if (old != null && old.price != p.price) {
            AuditLog.log(ctx, "PRICE_CHANGE", name + ": " + Fmt.number(old.price) + " -> " + Fmt.number(p.price));
        } else {
            AuditLog.log(ctx, "PRODUCT_EDIT", name);
        }
        return p.id;
    }

    public void setProductActive(long id, boolean active) {
        ContentValues cv = new ContentValues();
        cv.put("active", active ? 1 : 0);
        db().update("products", cv, "id=?", new String[]{String.valueOf(id)});
        AuditLog.log(ctx, "PRODUCT_ACTIVE", "#" + id + " = " + active);
    }

    /** الحذف لا يمس الطلبات القديمة لأن اسم الصنف وسعره منسوخان في سطور الطلب. */
    public void deleteProduct(long id) {
        Product p = getProduct(id);
        db().delete("products", "id=?", new String[]{String.valueOf(id)});
        AuditLog.log(ctx, "PRODUCT_DELETE", p == null ? "#" + id : p.name);
    }
}

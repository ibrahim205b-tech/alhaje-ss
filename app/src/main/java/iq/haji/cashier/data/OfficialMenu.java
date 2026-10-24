package iq.haji.cashier.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * القائمة الرسمية لمطعم الحجي. تُستورد من شاشة «الأصناف والأقسام».
 * الاستيراد لا يكرر صنفاً موجوداً بنفس القسم والاسم والسعر، ولا يحذف أي شيء:
 * في وضع الاستبدال تُخفى الأصناف والأقسام القديمة فقط حتى تبقى الطلبات والتقارير سليمة.
 * أصناف البيتزا متعددة الأحجام تُحفظ بالصيغة «الاسم - الحجم» وتظهر في الكاشير كبطاقة واحدة.
 */
public final class OfficialMenu {

    private OfficialMenu() {
    }

    /** نتيجة الاستيراد. */
    public static final class Result {
        public int categories;
        public int added;
        public int alreadyPresent;
    }

    private static final class Item {
        final String name;
        final long price;

        Item(String name, long price) {
            this.name = name;
            this.price = price;
        }
    }

    private static final class Section {
        final String name;
        final Item[] items;

        Section(String name, Item... items) {
            this.name = name;
            this.items = items;
        }
    }

    private static Item i(String name, long price) {
        return new Item(name, price);
    }

    private static final Section[] MENU = {
            new Section("الكنتاكي",
                    i("كنتاكي 3 قطع", 5000),
                    i("كنتاكي 5 قطع", 8000),
                    i("كنتاكي 10 قطع عائلي", 16000),
                    i("ستربس 10 قطع عائلي", 15000),
                    i("ستربس 7 قطع", 10000),
                    i("ستربس 4 قطع", 6000)),

            new Section("الدجاج",
                    i("دجاجة ذبح عراقي", 14000),
                    i("نص دجاجة ذبح عراقي", 8000),
                    i("دجاجة مشوية", 12000),
                    i("نص دجاجة مشوية", 7000),
                    i("ربع دجاجة ذبح عراقي", 5000),
                    i("ربع دجاجة عالتمَن", 4000)),

            new Section("البركر",
                    i("بركَر لحم كلاسك", 1000),
                    i("بركَر لحم بالجبن", 2000),
                    i("بركَر دجاج بالجبن", 2000),
                    i("بركَر لحم دبل", 3000)),

            new Section("الفلافل",
                    i("لفة فلافل عادي", 500),
                    i("لفة فلافل مشكل", 750),
                    i("صاج فلافل", 1000),
                    i("صينية فلافل كبيرة", 5000),
                    i("صينية فلافل وسط", 3000),
                    i("لفة قوزي دجاج", 1000),
                    i("لفة جلفراي", 1000),
                    i("لفة مغربية", 1000),
                    i("لفة كبدة دجاج", 1000)),

            new Section("الريزو",
                    i("ريزو أعشاب", 5000),
                    i("ريزو بالجبن", 5000),
                    i("ريزو مدخن", 5000),
                    i("ريزو هبلينو", 5000),
                    i("ريزو كلاسك", 5000),
                    i("ريزو شاورما دجاج", 5000),
                    i("ريزو شاورما لحم", 5000),
                    i("ريزو سبايسي", 5000),
                    i("ريزو", 3000)),

            new Section("المقبلات",
                    i("ماعون ثنائي", 1000),
                    i("خماسي", 2000),
                    i("صينية مشكل", 5000)),

            new Section("المشروبات",
                    i("بيبسي", 500),
                    i("سفن", 500),
                    i("ميريندا", 500),
                    i("لبن", 500),
                    i("بطل ببسي وسط", 750)),

            new Section("السندويشات",
                    i("سندويش شاورما لحم", 2000),
                    i("سندويش دجاج", 1500),
                    i("سندويش زنكر", 3000)),

            new Section("اللغَم",
                    i("لغَم لحم", 5000),
                    i("لغَم دجاج", 5000),
                    i("زنكر", 3000)),

            new Section("الشرقي",
                    i("منسف دجاج + لحم", 50000),
                    i("منسف سمك + دجاج", 40000),
                    i("منسف سمك + لحم", 60000),
                    i("منسف دجاج", 25000),
                    i("صينية تمن ومرق كبيرة", 10000),
                    i("صينية تمن ومرق وسط", 7000),
                    i("صينية تمن ومرق صغيرة", 5000),
                    i("نفر تمن ومرق", 3000)),

            new Section("الفنكر",
                    i("فنكر عادي", 1000),
                    i("فنكر بالجبن", 1500),
                    i("فنكر عائلي", 3000)),

            new Section("البيتزا",
                    i("بيتزا لحم - صغير", 5000),
                    i("بيتزا لحم - وسط", 7000),
                    i("بيتزا لحم - كبير", 10000),
                    i("بيتزا دجاج - صغير", 5000),
                    i("بيتزا دجاج - وسط", 7000),
                    i("بيتزا دجاج - كبير", 10000),
                    i("بيتزا الفصول الأربعة - صغير", 5000),
                    i("بيتزا الفصول الأربعة - وسط", 7000),
                    i("بيتزا الفصول الأربعة - كبير", 10000),
                    i("بيتزا مارجريتا - صغير", 4000),
                    i("بيتزا مارجريتا - وسط", 6000),
                    i("بيتزا مارجريتا - كبير", 8000),
                    i("بيتزا ببروني - صغير", 5000),
                    i("بيتزا ببروني - وسط", 7000),
                    i("بيتزا ببروني - كبير", 10000),
                    // صنفان بسعر وحجم واحد حسب القائمة الأصلية.
                    i("بيتزا لحم", 3000),
                    i("بيتزا دجاج", 3000)),

            new Section("المشويات (وزن)",
                    i("كيلو كباب لحم غنم (20 شيش) سفري", 20000),
                    i("نص كيلو كباب لحم غنم (10 شيش) سفري", 10000),
                    i("كيلو كباب دجاج (20 شيش) سفري", 16000),
                    i("نص كيلو كباب دجاج (10 شيش) سفري", 8000)),

            new Section("المشويات",
                    i("نفر كباب لحم غنم", 12000),
                    i("نصف نفر كباب لحم غنم", 6000),
                    i("نفر تكه لحم غنم", 14000),
                    i("نصف نفر تكه لحم غنم", 7000),
                    i("نفر كباب دجاج", 10000),
                    i("نصف نفر كباب دجاج", 5000),
                    i("نفر تكه دجاج متبله", 10000),
                    i("نصف نفر تكه دجاج متبله", 5000),
                    i("نفر معلاك غنم", 14000),
                    i("نصف نفر معلاك غنم", 7000),
                    i("نفر قلب غنم", 12000),
                    i("نصف نفر قلب غنم", 6000),
                    i("نفر جلاوي غنم", 12000),
                    i("نصف نفر جلاوي غنم", 6000),
                    i("نفر مسحب دجاج ناشف (نصف دجاجة)", 8000),
                    i("نفر مسحب دجاج عالتمَن (نصف دجاجة)", 10000),
                    i("دجاجة عالصجمع صينيه عالتمَن", 15000),
                    i("نصف دجاجة عالصجمع صينيه عالتمَن", 8000),
                    i("دجاجة عالصجمع ناشف صينيه بدون تمن", 13000),
                    i("نصف دجاجة عالصجمع ناشف صينيه بدون تمن", 7000)),

            new Section("المشويات (لفات)",
                    i("لفه كباب لحم غنم", 1500),
                    i("لفه كباب دجاج", 1500),
                    i("لفه تكه دجاج", 1500),
                    i("لفه تكه لحم", 2500),
                    i("لفه معلاك", 2500),
                    i("لفه قلب", 2500),
                    i("لفه جلاوي", 2500),
                    i("شيش ليه", 2500)),

            new Section("الشاورما",
                    i("صاج شاورما لحم", 2500),
                    i("صاج شاورما دجاج", 2000),
                    i("صاج ايطالي لحم", 5000),
                    i("صاج ايطالي دجاج", 5000)),
    };

    /** عدد الأصناف في القائمة الرسمية (للعرض في رسالة التأكيد). */
    public static int itemCount() {
        int n = 0;
        for (Section section : MENU) {
            n += section.items.length;
        }
        return n;
    }

    public static int sectionCount() {
        return MENU.length;
    }

    /**
     * يستورد القائمة الرسمية.
     *
     * @param replace true: يخفي كل الأصناف والأقسام الحالية ثم يُظهر القائمة الرسمية فقط.
     *                false: يضيف القائمة الرسمية بجانب الأصناف الحالية.
     */
    public static Result importMenu(Context context, boolean replace) {
        SQLiteDatabase db = DatabaseHelper.get(context).getWritableDatabase();
        Result result = new Result();

        db.beginTransaction();
        try {
            if (replace) {
                ContentValues hide = new ContentValues();
                hide.put("active", 0);
                db.update("products", hide, null, null);
                db.update("categories", hide, null, null);
            }

            for (int s = 0; s < MENU.length; s++) {
                Section section = MENU[s];
                long categoryId = findOrCreateCategory(db, section.name, s + 1);
                result.categories++;

                for (Item item : section.items) {
                    long productId = findProduct(db, categoryId, item);

                    if (productId > 0) {
                        ContentValues show = new ContentValues();
                        show.put("active", 1);
                        db.update("products", show, "id=?",
                                new String[]{String.valueOf(productId)});
                        result.alreadyPresent++;
                    } else {
                        ContentValues cv = new ContentValues();
                        cv.put("category_id", categoryId);
                        cv.put("name", item.name);
                        cv.put("price", item.price);
                        cv.put("active", 1);
                        db.insert("products", null, cv);
                        result.added++;
                    }
                }
            }

            AuditLog.log(db, "MENU_IMPORT",
                    (replace ? "استبدال" : "إضافة")
                            + ": أُضيف " + result.added
                            + "، موجود " + result.alreadyPresent);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        return result;
    }

    private static long findOrCreateCategory(SQLiteDatabase db, String name, int order) {
        long id = 0;

        try (Cursor c = db.rawQuery(
                "SELECT id FROM categories WHERE name=? ORDER BY id LIMIT 1",
                new String[]{name})) {
            if (c.moveToFirst()) {
                id = c.getLong(0);
            }
        }

        ContentValues cv = new ContentValues();
        cv.put("name", name);
        cv.put("sort_order", order);
        cv.put("active", 1);

        if (id > 0) {
            db.update("categories", cv, "id=?", new String[]{String.valueOf(id)});
            return id;
        }

        return db.insert("categories", null, cv);
    }

    private static long findProduct(SQLiteDatabase db, long categoryId, Item item) {
        try (Cursor c = db.rawQuery(
                "SELECT id FROM products WHERE category_id=? AND name=? AND price=? "
                        + "ORDER BY id LIMIT 1",
                new String[]{
                        String.valueOf(categoryId),
                        item.name,
                        String.valueOf(item.price)})) {
            if (c.moveToFirst()) {
                return c.getLong(0);
            }
        }

        return 0;
    }
}

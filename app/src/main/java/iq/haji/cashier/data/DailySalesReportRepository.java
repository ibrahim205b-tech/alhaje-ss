
package iq.haji.cashier.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import iq.haji.cashier.model.OrderStatus;

public class DailySalesReportRepository {

    public static class ItemSale {
        public String name;
        public long unitPrice;
        public long orderCount;
        public long quantity;
        public long total;
    }

    public static class CategorySale {
        public String name;
        public long total;
        public final List<ItemSale> items = new ArrayList<>();
    }

    /** طلبات اليوم المنسوبة لسائق واحد، بأرقام فواتيرها. */
    public static class DriverSale {
        public String name;
        public final List<Long> orderIds = new ArrayList<>();
    }

    public static class Report {
        public long from;
        public long toExclusive;
        public long orderCount;
        public long invoiceTotal;
        public long itemTotal;
        public final List<CategorySale> categories = new ArrayList<>();
        public final List<DriverSale> drivers = new ArrayList<>();
    }

    private static final String SALE_STATUSES =
            "('" + OrderStatus.PREPARING + "','" +
                    OrderStatus.READY + "','" +
                    OrderStatus.COMPLETED + "')";

    private final Context context;

    public DailySalesReportRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    private SQLiteDatabase db() {
        return DatabaseHelper.get(context).getReadableDatabase();
    }

    public Report load(long from, long toExclusive) {
        Report report = new Report();
        report.from = from;
        report.toExclusive = toExclusive;

        String summarySql =
                "SELECT COUNT(*), COALESCE(SUM(total),0) " +
                "FROM orders WHERE created_at>=? AND created_at<? " +
                "AND status IN " + SALE_STATUSES;

        try (Cursor c = db().rawQuery(summarySql, new String[]{
                String.valueOf(from), String.valueOf(toExclusive)})) {
            if (c.moveToFirst()) {
                report.orderCount = c.getLong(0);
                report.invoiceTotal = c.getLong(1);
            }
        }

        Map<String, CategorySale> grouped = new LinkedHashMap<>();

        String itemSql =
                "SELECT COALESCE(c.name,'غير مصنف'), i.name, i.unit_price, " +
                "COUNT(DISTINCT o.id), COALESCE(SUM(i.qty),0), " +
                "COALESCE(SUM(i.qty*i.unit_price),0) " +
                "FROM order_items i " +
                "JOIN orders o ON o.id=i.order_id " +
                "LEFT JOIN products p ON p.id=i.product_id " +
                "LEFT JOIN categories c ON c.id=p.category_id " +
                "WHERE o.created_at>=? AND o.created_at<? " +
                "AND o.status IN " + SALE_STATUSES +
                " GROUP BY COALESCE(c.name,'غير مصنف'), i.name, i.unit_price " +
                "ORDER BY COALESCE(c.name,'غير مصنف'), i.name, i.unit_price";

        try (Cursor c = db().rawQuery(itemSql, new String[]{
                String.valueOf(from), String.valueOf(toExclusive)})) {
            while (c.moveToNext()) {
                String categoryName = c.getString(0);
                CategorySale category = grouped.get(categoryName);

                if (category == null) {
                    category = new CategorySale();
                    category.name = categoryName;
                    grouped.put(categoryName, category);
                }

                ItemSale item = new ItemSale();
                item.name = c.getString(1);
                item.unitPrice = c.getLong(2);
                item.orderCount = c.getLong(3);
                item.quantity = c.getLong(4);
                item.total = c.getLong(5);

                category.items.add(item);
                category.total += item.total;
                report.itemTotal += item.total;
            }
        }

        report.categories.addAll(grouped.values());

        // السائقون: عدد الطلبات وأرقام الفواتير لكل سائق.
        Map<String, DriverSale> byDriver = new LinkedHashMap<>();

        String driverSql =
                "SELECT TRIM(driver), id FROM orders " +
                "WHERE created_at>=? AND created_at<? " +
                "AND status IN " + SALE_STATUSES +
                " AND driver IS NOT NULL AND TRIM(driver)<>'' " +
                "ORDER BY TRIM(driver), id";

        try (Cursor c = db().rawQuery(driverSql, new String[]{
                String.valueOf(from), String.valueOf(toExclusive)})) {
            while (c.moveToNext()) {
                String driverName = c.getString(0);
                DriverSale driver = byDriver.get(driverName);

                if (driver == null) {
                    driver = new DriverSale();
                    driver.name = driverName;
                    byDriver.put(driverName, driver);
                }

                driver.orderIds.add(c.getLong(1));
            }
        }

        report.drivers.addAll(byDriver.values());
        return report;
    }
}

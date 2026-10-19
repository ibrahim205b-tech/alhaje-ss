package iq.haji.cashier.data;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.List;

import iq.haji.cashier.model.DayTotal;
import iq.haji.cashier.model.OrderStatus;
import iq.haji.cashier.model.OrderType;
import iq.haji.cashier.model.SalesSummary;
import iq.haji.cashier.model.TopItem;

/** كل الأرقام هنا تُحسب بـ SQL من الطلبات المحفوظة، لا توجد أرقام ثابتة. */
public class ReportRepository {
    private static final String SALE_STATUSES = "('" + OrderStatus.PREPARING + "','"
            + OrderStatus.READY + "','" + OrderStatus.COMPLETED + "')";

    private final Context ctx;

    public ReportRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    private SQLiteDatabase db() {
        return DatabaseHelper.get(ctx).getWritableDatabase();
    }

    public SalesSummary summaryForShift(long shiftId) {
        return summary("shift_id=?", new String[]{String.valueOf(shiftId)});
    }

    /** من from (شامل) إلى toExclusive (غير شامل) بالميلي ثانية. */
    public SalesSummary summaryForRange(long from, long toExclusive) {
        return summary("created_at>=? AND created_at<?",
                new String[]{String.valueOf(from), String.valueOf(toExclusive)});
    }

    private SalesSummary summary(String where, String[] args) {
        SalesSummary s = new SalesSummary();
        String sql = "SELECT status,type,COUNT(*),COALESCE(SUM(total),0),COALESCE(SUM(discount),0),"
                + "COALESCE(SUM(delivery_fee),0),COALESCE(SUM(subtotal),0) FROM orders WHERE "
                + where + " GROUP BY status,type";
        try (Cursor c = db().rawQuery(sql, args)) {
            while (c.moveToNext()) {
                String status = c.getString(0);
                String type = c.getString(1);
                int cnt = c.getInt(2);
                long total = c.getLong(3);
                long disc = c.getLong(4);
                long deliv = c.getLong(5);
                long sub = c.getLong(6);

                if (OrderStatus.CANCELLED.equals(status)) {
                    s.cancelledCount += cnt;
                    s.cancelledTotal += total;
                } else if (OrderStatus.countsAsSale(status)) {
                    s.ordersCount += cnt;
                    if (OrderStatus.COMPLETED.equals(status)) s.completedCount += cnt;
                    s.salesTotal += total;
                    s.discountsTotal += disc;
                    s.deliveryTotal += deliv;
                    s.subtotalTotal += sub;
                    if (OrderType.DELIVERY.equals(type)) {
                        s.deliveryOrdersCount += cnt;
                        s.deliveryOrdersTotal += total;
                    } else if (OrderType.DINE_IN.equals(type)) {
                        s.dineInCount += cnt;
                        s.dineInTotal += total;
                    } else {
                        s.takeawayCount += cnt;
                        s.takeawayTotal += total;
                    }
                } else {
                    s.pendingCount += cnt;
                }
            }
        }
        return s;
    }

    public List<TopItem> topItems(long from, long toExclusive, int limit) {
        List<TopItem> out = new ArrayList<>();
        String sql = "SELECT i.name, SUM(i.qty), SUM(i.qty*i.unit_price) FROM order_items i "
                + "JOIN orders o ON o.id=i.order_id "
                + "WHERE o.created_at>=? AND o.created_at<? AND o.status IN " + SALE_STATUSES
                + " GROUP BY i.name ORDER BY SUM(i.qty) DESC, SUM(i.qty*i.unit_price) DESC LIMIT ?";
        try (Cursor c = db().rawQuery(sql, new String[]{String.valueOf(from),
                String.valueOf(toExclusive), String.valueOf(limit)})) {
            while (c.moveToNext()) {
                TopItem t = new TopItem();
                t.name = c.getString(0);
                t.qty = c.getLong(1);
                t.revenue = c.getLong(2);
                out.add(t);
            }
        }
        return out;
    }

    public List<TopItem> topItemsForShift(long shiftId, int limit) {
        List<TopItem> out = new ArrayList<>();
        String sql = "SELECT i.name, SUM(i.qty), SUM(i.qty*i.unit_price) FROM order_items i "
                + "JOIN orders o ON o.id=i.order_id "
                + "WHERE o.shift_id=? AND o.status IN " + SALE_STATUSES
                + " GROUP BY i.name ORDER BY SUM(i.qty) DESC LIMIT ?";
        try (Cursor c = db().rawQuery(sql, new String[]{String.valueOf(shiftId), String.valueOf(limit)})) {
            while (c.moveToNext()) {
                TopItem t = new TopItem();
                t.name = c.getString(0);
                t.qty = c.getLong(1);
                t.revenue = c.getLong(2);
                out.add(t);
            }
        }
        return out;
    }

    /** مبيعات كل يوم ضمن فترة (بالتوقيت المحلي). */
    public List<DayTotal> dailyTotals(long from, long toExclusive) {
        List<DayTotal> out = new ArrayList<>();
        String sql = "SELECT strftime('%Y-%m-%d', created_at/1000, 'unixepoch', 'localtime') AS d, "
                + "COUNT(*), COALESCE(SUM(total),0) FROM orders "
                + "WHERE created_at>=? AND created_at<? AND status IN " + SALE_STATUSES
                + " GROUP BY d ORDER BY d DESC";
        try (Cursor c = db().rawQuery(sql, new String[]{String.valueOf(from), String.valueOf(toExclusive)})) {
            while (c.moveToNext()) {
                DayTotal d = new DayTotal();
                d.day = c.getString(0);
                d.orders = c.getInt(1);
                d.total = c.getLong(2);
                out.add(d);
            }
        }
        return out;
    }
}

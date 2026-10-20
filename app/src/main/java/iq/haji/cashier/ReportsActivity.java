
package iq.haji.cashier;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import iq.haji.cashier.data.CsvExporter;
import iq.haji.cashier.data.OrderRepository;
import iq.haji.cashier.data.ReportRepository;
import iq.haji.cashier.data.ShiftRepository;
import iq.haji.cashier.model.DayTotal;
import iq.haji.cashier.model.Order;
import iq.haji.cashier.model.SalesSummary;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.model.TopItem;
import iq.haji.cashier.print.DailySalesReportPrinter;
import iq.haji.cashier.ui.RowAdapter;
import iq.haji.cashier.ui.ShiftReportDialog;
import iq.haji.cashier.util.DateUtil;
import iq.haji.cashier.util.Fmt;
import iq.haji.cashier.util.Ui;
import iq.haji.cashier.util.UiKit;

/** كل الأرقام من SQL على الطلبات المحفوظة. الاستعلامات تعمل في خيط خلفي. */
public class ReportsActivity extends PageActivity {

    private static class Data {
        SalesSummary sum;
        List<TopItem> top;
        List<DayTotal> days;
        List<Shift> shifts;
        List<Order> orders;
    }

    private static final String[] PERIODS = {
            "اليوم",
            "أمس",
            "آخر 7 أيام",
            "هذا الشهر",
            "فترة محددة"
    };

    private static final String[] TABS = {
            "الأكثر مبيعاً",
            "المبيعات اليومية",
            "الورديات",
            "الطلبات"
    };

    private final TextView[] periodChips = new TextView[5];
    private final TextView[] tabChips = new TextView[4];

    private int period = 0;
    private int tab = 0;
    private int seq = 0;

    private long from;
    private long toEx;
    private long customFrom;
    private long customTo;

    private Data data;

    private LinearLayout summaryBox;
    private LinearLayout customRow;

    private TextView tvPeriod;
    private TextView btnFrom;
    private TextView btnTo;
    private TextView tvEmpty;

    private EditText etOrderNo;
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setPageTitle("تقارير المبيعات والطلبات");

        customFrom = customTo =
                DateUtil.startOfDay(System.currentTimeMillis());

        LinearLayout h = UiKit.row(this);

        content.addView(
                h,
                new FrameLayout.LayoutParams(
                        UiKit.MATCH,
                        UiKit.MATCH
                )
        );

        LinearLayout left = UiKit.column(this, h, 4, false);

        left.addView(UiKit.title(this, "الفترة"));

        LinearLayout pr = UiKit.chipRow(
                this,
                PERIODS,
                periodChips,
                0,
                i -> {
                    period = i;
                    customRow.setVisibility(
                            i == 4 ? View.VISIBLE : View.GONE
                    );
                    load();
                }
        );

        left.addView(
                UiKit.scrollRow(this, pr),
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8)
        );

        customRow = UiKit.row(this);

        btnFrom = UiKit.button(this, "", false);
        btnTo = UiKit.button(this, "", false);

        btnFrom.setOnClickListener(v -> pickDate(true));
        btnTo.setOnClickListener(v -> pickDate(false));

        customRow.addView(
                btnFrom,
                new LinearLayout.LayoutParams(0, UiKit.WRAP, 1f)
        );

        LinearLayout.LayoutParams p2 =
                new LinearLayout.LayoutParams(0, UiKit.WRAP, 1f);

        p2.setMarginStart(Ui.dp(this, 8));

        customRow.addView(btnTo, p2);

        customRow.setVisibility(View.GONE);

        left.addView(
                customRow,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8)
        );

        updateCustomButtons();

        tvPeriod = UiKit.label(this, "");

        left.addView(
                tvPeriod,
                UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 8)
        );

        summaryBox = UiKit.card(this);

        left.addView(
                summaryBox,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8)
        );

        // البحث والتصدير والطباعة
        LinearLayout tools = UiKit.card(this);

        tools.addView(UiKit.title(this, "بحث وتصدير وطباعة"));

        LinearLayout sr = UiKit.row(this);
        sr.setGravity(Gravity.CENTER_VERTICAL);

        etOrderNo = Ui.editText(
                this,
                "رقم الطلب",
                InputType.TYPE_CLASS_NUMBER,
                ""
        );

        sr.addView(
                etOrderNo,
                new LinearLayout.LayoutParams(0, UiKit.WRAP, 1f)
        );

        TextView open = UiKit.button(this, "فتح", true);

        open.setOnClickListener(v -> openOrderByNumber());

        LinearLayout.LayoutParams op =
                new LinearLayout.LayoutParams(
                        UiKit.WRAP,
                        UiKit.WRAP
                );

        op.setMarginStart(Ui.dp(this, 8));

        sr.addView(open, op);

        tools.addView(
                sr,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 10)
        );

        // تصدير الطلبات
        TextView ex1 = UiKit.button(
                this,
                "تصدير طلبات الفترة CSV",
                false
        );

        ex1.setOnClickListener(v -> exportOrders());

        tools.addView(
                ex1,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 10)
        );

        // تصدير الورديات
        TextView ex2 = UiKit.button(
                this,
                "تصدير سجل الورديات CSV",
                false
        );

        ex2.setOnClickListener(v -> exportShifts());

        tools.addView(
                ex2,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8)
        );

        // طباعة تقرير مبيعات اليوم مباشرة على الطابعة الحرارية
        TextView printDaily = UiKit.button(
                this,
                "طباعة مبيعات اليوم",
                true
        );

        printDaily.setOnClickListener(v -> {
            printDaily.setEnabled(false);

            new DailySalesReportPrinter(this).printToday(
                    (success, message) -> {
                        printDaily.setEnabled(true);
                        Ui.toast(this, message);
                    }
            );
        });

        tools.addView(
                printDaily,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8)
        );

        left.addView(
                tools,
                UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12)
        );

        // الجهة اليمنى: قوائم التقارير
        LinearLayout rightCol = UiKit.vertical(this);

        h.addView(
                rightCol,
                UiKit.weight(this, 5, 12)
        );

        LinearLayout tr = UiKit.chipRow(
                this,
                TABS,
                tabChips,
                0,
                i -> {
                    tab = i;
                    renderTab();
                }
        );

        rightCol.addView(
                UiKit.scrollRow(this, tr),
                new LinearLayout.LayoutParams(
                        UiKit.MATCH,
                        UiKit.WRAP
                )
        );

        FrameLayout frame = new FrameLayout(this);

        RecyclerView rv = new RecyclerView(this);

        rv.setLayoutManager(new LinearLayoutManager(this));

        adapter = new RowAdapter(this::onRowClick);

        rv.setAdapter(adapter);

        frame.addView(
                rv,
                new FrameLayout.LayoutParams(
                        UiKit.MATCH,
                        UiKit.MATCH
                )
        );

        tvEmpty = UiKit.text(
                this,
                "لا توجد بيانات في هذه الفترة",
                18,
                R.color.text_secondary,
                false
        );

        frame.addView(
                tvEmpty,
                new FrameLayout.LayoutParams(
                        UiKit.WRAP,
                        UiKit.WRAP,
                        Gravity.CENTER
                )
        );

        LinearLayout.LayoutParams fp =
                new LinearLayout.LayoutParams(
                        UiKit.MATCH,
                        0,
                        1f
                );

        fp.topMargin = Ui.dp(this, 8);

        rightCol.addView(frame, fp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    // ======================= الفترات =======================

    private long daysAgoStart(int days) {
        Calendar c = Calendar.getInstance();

        c.setTimeInMillis(
                DateUtil.startOfDay(System.currentTimeMillis())
        );

        c.add(Calendar.DAY_OF_MONTH, -days);

        return c.getTimeInMillis();
    }

    private void computeRange() {
        long now = System.currentTimeMillis();

        switch (period) {
            case 0:
                from = daysAgoStart(0);
                toEx = DateUtil.startOfNextDay(now);
                break;

            case 1:
                from = daysAgoStart(1);
                toEx = daysAgoStart(0);
                break;

            case 2:
                from = daysAgoStart(6);
                toEx = DateUtil.startOfNextDay(now);
                break;

            case 3:
                Calendar c = Calendar.getInstance();

                c.setTimeInMillis(DateUtil.startOfDay(now));
                c.set(Calendar.DAY_OF_MONTH, 1);

                from = c.getTimeInMillis();
                toEx = DateUtil.startOfNextDay(now);
                break;

            default:
                from = customFrom;
                toEx = DateUtil.startOfNextDay(customTo);
                break;
        }
    }

    private void pickDate(final boolean isFrom) {
        Calendar c = Calendar.getInstance();

        c.setTimeInMillis(isFrom ? customFrom : customTo);

        new DatePickerDialog(
                this,
                (view, y, m, d) -> {
                    long ms = DateUtil.startOfDay(y, m, d);

                    if (isFrom) {
                        customFrom = ms;
                    } else {
                        customTo = ms;
                    }

                    if (customTo < customFrom) {
                        Ui.toast(this, "تاريخ النهاية قبل البداية");

                        if (isFrom) {
                            customTo = customFrom;
                        } else {
                            customFrom = customTo;
                        }
                    }

                    updateCustomButtons();
                    load();
                },
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH),
                c.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void updateCustomButtons() {
        btnFrom.setText("من: " + Fmt.date(customFrom));
        btnTo.setText("إلى: " + Fmt.date(customTo));
    }

    // ======================= التحميل والعرض =======================

    private void load() {
        computeRange();

        final long f = from;
        final long t = toEx;
        final int my = ++seq;

        tvPeriod.setText(
                "الفترة: " + Fmt.date(f) + "  →  " + Fmt.date(t - 1)
        );

        this.<Data>bg(() -> {
            Data d = new Data();

            ReportRepository rr = new ReportRepository(this);

            d.sum = rr.summaryForRange(f, t);
            d.top = rr.topItems(f, t, 50);
            d.days = rr.dailyTotals(f, t);

            d.shifts = new ArrayList<>();

            for (Shift s : new ShiftRepository(this).listShifts(500)) {
                if (s.startedAt >= f && s.startedAt < t) {
                    d.shifts.add(s);
                }
            }

            d.orders = new OrderRepository(this)
                    .listOrders(0, null, f, t, null, 500);

            return d;

        }, d -> {
            if (my != seq) {
                return;
            }

            data = d;

            renderSummary();
            renderTab();
        });
    }

    private void renderSummary() {
        SalesSummary s = data.sum;

        summaryBox.removeAllViews();

        summaryBox.addView(UiKit.title(this, "ملخص الفترة"));

        summaryBox.addView(
                UiKit.kv(
                        this,
                        "إجمالي المبيعات",
                        Fmt.money(s.salesTotal),
                        true
                )
        );

        summaryBox.addView(
                UiKit.kv(
                        this,
                        "عدد الطلبات",
                        String.valueOf(s.ordersCount),
                        false
                )
        );

        summaryBox.addView(
                UiKit.kv(
                        this,
                        "المكتملة",
                        String.valueOf(s.completedCount),
                        false
                )
        );

        summaryBox.addView(
                UiKit.kv(
                        this,
                        "الملغاة",
                        s.cancelledCount + "  (" +
                                Fmt.money(s.cancelledTotal) + ")",
                        false
                )
        );

        if (s.pendingCount > 0) {
            summaryBox.addView(
                    UiKit.kv(
                            this,
                            "بانتظار الطباعة (غير محتسبة)",
                            String.valueOf(s.pendingCount),
                            false
                    )
            );
        }

        long avg = s.ordersCount == 0
                ? 0
                : s.salesTotal / s.ordersCount;

        summaryBox.addView(
                UiKit.kv(this, "متوسط الطلب", Fmt.money(avg), false)
        );

        summaryBox.addView(
                UiKit.kv(
                        this,
                        "مجموع الخصومات",
                        Fmt.money(s.discountsTotal),
                        false
                )
        );

        summaryBox.addView(
                UiKit.kv(
                        this,
                        "مجموع أجور التوصيل",
                        Fmt.money(s.deliveryTotal),
                        false
                )
        );

        summaryBox.addView(
                UiKit.kv(
                        this,
                        "سفري",
                        s.takeawayCount + "  (" +
                                Fmt.money(s.takeawayTotal) + ")",
                        false
                )
        );

        summaryBox.addView(
                UiKit.kv(
                        this,
                        "دليفري",
                        s.deliveryOrdersCount + "  (" +
                                Fmt.money(s.deliveryOrdersTotal) + ")",
                        false
                )
        );

        summaryBox.addView(
                UiKit.kv(
                        this,
                        "داخل المطعم",
                        s.dineInCount + "  (" +
                                Fmt.money(s.dineInTotal) + ")",
                        false
                )
        );
    }

    private void renderTab() {
        if (data == null) {
            return;
        }

        List<RowAdapter.Row> rows = new ArrayList<>();

        if (tab == 0) {
            int i = 1;

            for (TopItem t : data.top) {
                rows.add(
                        new RowAdapter.Row(
                                i++ + ".  " + t.name,
                                "الكمية المباعة: " + t.qty,
                                Fmt.money(t.revenue),
                                null
                        )
                );
            }

        } else if (tab == 1) {
            for (DayTotal d : data.days) {
                rows.add(
                        new RowAdapter.Row(
                                d.day,
                                d.orders + " طلب",
                                Fmt.money(d.total),
                                null
                        )
                );
            }

        } else if (tab == 2) {
            for (Shift s : data.shifts) {
                RowAdapter.Row r = new RowAdapter.Row(
                        "الوردية #" + s.id,
                        Fmt.dateTime(s.startedAt) + "  •  " +
                                (s.cashierName == null ? "" : s.cashierName),
                        s.closed ? Fmt.money(s.salesTotal) : null,
                        Long.valueOf(s.id)
                );

                r.badge(
                        s.closed ? "مغلقة" : "مفتوحة",
                        s.closed
                                ? R.color.text_secondary
                                : R.color.success
                );

                rows.add(r);
            }

        } else {
            for (Order o : data.orders) {
                rows.add(RowAdapter.orderRow(o));
            }
        }

        adapter.setRows(rows);

        tvEmpty.setVisibility(
                rows.isEmpty() ? View.VISIBLE : View.GONE
        );
    }

    private void onRowClick(RowAdapter.Row r) {
        if (!(r.tag instanceof Long)) {
            return;
        }

        long id = (Long) r.tag;

        if (tab == 2) {
            ShiftReportDialog.show(this, id);

        } else if (tab == 3) {
            openOrder(id);
        }
    }

    private void openOrder(long id) {
        Intent i = new Intent(this, OrderDetailsActivity.class);

        i.putExtra("order_id", id);

        startActivity(i);
    }

    private void openOrderByNumber() {
        long id = Fmt.parseMoney(
                etOrderNo.getText().toString()
        );

        if (id <= 0) {
            etOrderNo.setError("أدخل رقم الطلب");
            return;
        }

        if (new OrderRepository(this).getOrder(id) == null) {
            Ui.toast(this, "لا يوجد طلب بالرقم " + id);
            return;
        }

        openOrder(id);
    }

    // ======================= التصدير =======================

    private String stamp(long ms) {
        return new SimpleDateFormat(
                "yyyyMMdd",
                Locale.US
        ).format(new Date(ms));
    }

    private void exportOrders() {
        final long f = from;
        final long t = toEx;

        this.<File>bg(
                () -> CsvExporter.exportOrders(
                        this,
                        f,
                        t,
                        "orders_" + stamp(f) + "_" + stamp(t - 1)
                ),
                file -> startActivity(
                        CsvExporter.shareIntent(this, file)
                )
        );
    }

    private void exportShifts() {
        this.<File>bg(
                () -> CsvExporter.exportShifts(
                        this,
                        "shifts_" + stamp(System.currentTimeMillis())
                ),
                file -> startActivity(
                        CsvExporter.shareIntent(this, file)
                )
        );
    }
}

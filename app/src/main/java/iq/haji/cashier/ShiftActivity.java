package iq.haji.cashier;

import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import iq.haji.cashier.data.AppSettings;
import iq.haji.cashier.data.CsvExporter;
import iq.haji.cashier.data.ShiftRepository;
import iq.haji.cashier.model.SalesSummary;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.ui.RowAdapter;
import iq.haji.cashier.ui.ShiftReportDialog;
import iq.haji.cashier.util.AppException;
import iq.haji.cashier.util.Fmt;
import iq.haji.cashier.util.PinDialog;
import iq.haji.cashier.util.Ui;
import iq.haji.cashier.util.UiKit;

public class ShiftActivity extends PageActivity {
    private ShiftRepository repo;
    private AppSettings st;
    private LinearLayout left;
    private RowAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageTitle("الوردية");
        repo = new ShiftRepository(this);
        st = new AppSettings(this);

        LinearLayout h = UiKit.row(this);
        content.addView(h, new FrameLayout.LayoutParams(UiKit.MATCH, UiKit.MATCH));
        left = UiKit.column(this, h, 5, false);

        LinearLayout hist = UiKit.card(this);
        h.addView(hist, UiKit.weight(this, 4, 12));
        hist.addView(UiKit.title(this, "سجل الورديات"));
        hist.addView(UiKit.hint(this, "اضغط وردية لعرض تقريرها وتصدير طلباتها"));
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter(r -> ShiftReportDialog.show(this, (Long) r.tag));
        rv.setAdapter(adapter);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(UiKit.MATCH, 0, 1f);
        rp.topMargin = Ui.dp(this, 8);
        hist.addView(rv, rp);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        left.removeAllViews();
        Shift open;
        try {
            open = repo.ensureOpenShift();
        } catch (AppException e) {
            open = null;
        }
        if (open == null) buildStart();
        else buildOpen(open);

        List<RowAdapter.Row> rows = new ArrayList<>();
        for (Shift s : repo.listShifts(200)) {
            RowAdapter.Row r = new RowAdapter.Row("الوردية #" + s.id,
                    Fmt.dateTime(s.startedAt) + "  •  " + (s.cashierName == null ? "" : s.cashierName),
                    s.closed ? Fmt.money(s.salesTotal) : null, Long.valueOf(s.id));
            r.badge(s.closed ? "مغلقة" : "مفتوحة", s.closed ? R.color.text_secondary : R.color.success);
            rows.add(r);
        }
        adapter.setRows(rows);
    }

    private void buildStart() {
        LinearLayout c = UiKit.card(this);
        c.addView(UiKit.title(this, "لا توجد وردية مفتوحة"));
        c.addView(UiKit.hint(this, "ابدأ وردية لتتمكن من حفظ الطلبات وطباعتها."));
        c.addView(UiKit.label(this, "اسم الموظف"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        final EditText etName = Ui.editText(this, "اسم الموظف", InputType.TYPE_CLASS_TEXT,
                st.get(AppSettings.CASHIER_NAME, "الكاشير"));
        c.addView(etName);
        c.addView(UiKit.label(this, "مبلغ افتتاح الصندوق (د.ع)"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        final EditText etOpen = Ui.editText(this, "0", InputType.TYPE_CLASS_NUMBER, "");
        c.addView(etOpen);
        TextView go = UiKit.button(this, "بدء وردية", true);
        go.setOnClickListener(v -> {
            long open = 0;
            String s = etOpen.getText().toString().trim();
            if (!s.isEmpty()) {
                open = Fmt.parseMoney(s);
                if (open < 0) {
                    etOpen.setError("مبلغ غير صحيح");
                    return;
                }
            }
            try {
                repo.startShift(etName.getText().toString(), open);
                Ui.toast(this, "بدأت الوردية");
                render();
            } catch (AppException e) {
                Ui.toast(this, e.getMessage());
            }
        });
        c.addView(go, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 16));
        left.addView(c, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 0));
    }

    private void buildOpen(final Shift s) {
        final SalesSummary sum = repo.currentSummary(s.id);
        final int pending = repo.pendingPrintCount(s.id);
        final long expected = s.openingCash + sum.salesTotal;

        LinearLayout c = UiKit.card(this);
        c.addView(UiKit.title(this, "الوردية الحالية — مفتوحة"));
        c.addView(UiKit.kv(this, "الموظف", s.cashierName, false), UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
        c.addView(UiKit.kv(this, "بدأت", Fmt.dateTime(s.startedAt), false));
        c.addView(UiKit.kv(this, "افتتاح الصندوق", Fmt.money(s.openingCash), false));
        c.addView(UiKit.kv(this, "عدد الطلبات", String.valueOf(sum.ordersCount), false));
        c.addView(UiKit.kv(this, "الطلبات الملغاة", String.valueOf(sum.cancelledCount), false));
        c.addView(UiKit.kv(this, "مجموع الخصومات", Fmt.money(sum.discountsTotal), false));
        c.addView(UiKit.kv(this, "مجموع أجور التوصيل", Fmt.money(sum.deliveryTotal), false));
        c.addView(UiKit.kv(this, "إجمالي المبيعات", Fmt.money(sum.salesTotal), true));
        c.addView(UiKit.kv(this, "المتوقع في الصندوق", Fmt.money(expected), false));
        if (pending > 0) {
            TextView w = UiKit.text(this, "⚠ يوجد " + pending
                    + " طلب بانتظار الطباعة. أعد طباعته أو ألغِه من شاشة الطلبات قبل الإغلاق.",
                    14, R.color.warning, true);
            c.addView(w, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 10));
        }
        left.addView(c, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 0));

        LinearLayout close = UiKit.card(this);
        close.addView(UiKit.title(this, "إغلاق الوردية"));
        close.addView(UiKit.label(this, "المبلغ الفعلي في الصندوق (د.ع)"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 10));
        final EditText etCash = Ui.editText(this, "عدّ النقد ثم أدخله", InputType.TYPE_CLASS_NUMBER, "");
        close.addView(etCash);
        close.addView(UiKit.label(this, "ملاحظة (اختيارية)"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 10));
        final EditText etNote = Ui.editText(this, "", InputType.TYPE_CLASS_TEXT, "");
        close.addView(etNote);
        TextView btnClose = UiKit.button(this, "إغلاق الوردية", true);
        btnClose.setOnClickListener(v -> {
            long cash = Fmt.parseMoney(etCash.getText().toString());
            if (cash < 0) {
                etCash.setError("أدخل المبلغ الفعلي في الصندوق");
                return;
            }
            confirmClose(s, cash, etNote.getText().toString(), expected);
        });
        close.addView(btnClose, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 14));
        TextView btnExport = UiKit.button(this, "تصدير طلبات الوردية CSV", false);
        btnExport.setOnClickListener(v -> this.<File>bg(
                () -> CsvExporter.exportShiftOrders(this, s.id, "shift_" + s.id),
                f -> startActivity(CsvExporter.shareIntent(this, f))));
        close.addView(btnExport, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));
        left.addView(close, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 12));
    }

    private void confirmClose(final Shift s, final long cash, final String note, long expected) {
        long diff = cash - expected;
        String msg = "المتوقع: " + Fmt.money(expected) + "\nالفعلي: " + Fmt.money(cash)
                + "\nالفرق: " + (diff > 0 ? "+" : "") + Fmt.money(diff)
                + "\n\nبعد الإغلاق يُحفظ تقرير الوردية، ثم تبدأ وردية جديدة فوراً من الصفر"
                + " ويعود رقم الفاتورة إلى #1. هل تريد الإغلاق؟";
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("تأكيد إغلاق الوردية")
                .setMessage(msg)
                .setPositiveButton("تأكيد الإغلاق", (x, w) -> PinDialog.require(this, () -> doClose(s.id, cash, note)))
                .setNegativeButton(R.string.back, null)
                .create();
        Ui.showRtl(d);
    }

    private void doClose(long id, long cash, String note) {
        try {
            Shift closed = repo.closeShift(id, cash, note);
            Ui.toast(this, "أُغلقت الوردية وبدأت وردية جديدة — الفاتورة التالية #1");
            render();
            ShiftReportDialog.show(this, closed.id);
        } catch (AppException e) {
            AlertDialog d = new AlertDialog.Builder(this)
                    .setTitle("تعذر إغلاق الوردية")
                    .setMessage(e.getMessage())
                    .setPositiveButton(R.string.ok, null)
                    .create();
            Ui.showRtl(d);
        }
    }
}

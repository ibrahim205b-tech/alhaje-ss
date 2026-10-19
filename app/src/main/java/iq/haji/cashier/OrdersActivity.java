package iq.haji.cashier;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import iq.haji.cashier.data.OrderRepository;
import iq.haji.cashier.data.ShiftRepository;
import iq.haji.cashier.model.Order;
import iq.haji.cashier.model.OrderStatus;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.ui.RowAdapter;
import iq.haji.cashier.util.DateUtil;
import iq.haji.cashier.util.Ui;
import iq.haji.cashier.util.UiKit;

public class OrdersActivity extends PageActivity {
    private static final String[] STATUS_CODES = {null, OrderStatus.PENDING_PRINT, OrderStatus.PREPARING,
            OrderStatus.READY, OrderStatus.COMPLETED, OrderStatus.CANCELLED};
    private static final String[] STATUS_LABELS = {"الكل", "بانتظار الطباعة", "قيد التجهيز", "جاهز للتسليم",
            "مكتمل", "ملغى"};
    private static final String[] SCOPE_LABELS = {"الوردية الحالية", "اليوم", "الكل"};

    private OrderRepository repo;
    private ShiftRepository shifts;
    private RowAdapter adapter;
    private EditText etSearch;
    private TextView tvEmpty;
    private final TextView[] scopeChips = new TextView[3];
    private final TextView[] statusChips = new TextView[6];
    private int scope = 1;
    private int statusIdx = 0;
    private int seq = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageTitle("الطلبات السابقة");
        repo = new OrderRepository(this);
        shifts = new ShiftRepository(this);

        LinearLayout col = UiKit.vertical(this);
        content.addView(col, new FrameLayout.LayoutParams(UiKit.MATCH, UiKit.MATCH));

        LinearLayout top = UiKit.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        etSearch = Ui.editText(this, "🔍  رقم الطلب أو اسم/هاتف الزبون", InputType.TYPE_CLASS_TEXT, "");
        top.addView(etSearch, new LinearLayout.LayoutParams(0, UiKit.WRAP, 1f));
        LinearLayout scopeRow = UiKit.chipRow(this, SCOPE_LABELS, scopeChips, scope, i -> {
            scope = i;
            load();
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(UiKit.WRAP, UiKit.WRAP);
        sp.setMarginStart(Ui.dp(this, 12));
        top.addView(scopeRow, sp);
        col.addView(top, new LinearLayout.LayoutParams(UiKit.MATCH, UiKit.WRAP));

        LinearLayout statusRow = UiKit.chipRow(this, STATUS_LABELS, statusChips, statusIdx, i -> {
            statusIdx = i;
            load();
        });
        col.addView(UiKit.scrollRow(this, statusRow), UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 10));

        FrameLayout frame = new FrameLayout(this);
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RowAdapter(r -> {
            Intent i = new Intent(this, OrderDetailsActivity.class);
            i.putExtra("order_id", (Long) r.tag);
            startActivity(i);
        });
        rv.setAdapter(adapter);
        frame.addView(rv, new FrameLayout.LayoutParams(UiKit.MATCH, UiKit.MATCH));
        tvEmpty = UiKit.text(this, "لا توجد طلبات", 18, iq.haji.cashier.R.color.text_secondary, false);
        FrameLayout.LayoutParams ep = new FrameLayout.LayoutParams(UiKit.WRAP, UiKit.WRAP, Gravity.CENTER);
        frame.addView(tvEmpty, ep);
        col.addView(frame, new LinearLayout.LayoutParams(UiKit.MATCH, 0, 1f));

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                load();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        final int my = ++seq;
        final String q = etSearch.getText().toString();
        final int sc = scope;
        final String st = STATUS_CODES[statusIdx];
        this.<List<Order>>bg(() -> {
            long shiftId = 0, from = 0, to = 0;
            if (sc == 0) {
                Shift s = shifts.getOpenShift();
                if (s == null) return new ArrayList<Order>();
                shiftId = s.id;
            } else if (sc == 1) {
                long now = System.currentTimeMillis();
                from = DateUtil.startOfDay(now);
                to = DateUtil.startOfNextDay(now);
            }
            return repo.listOrders(shiftId, st, from, to, q, 400);
        }, list -> {
            if (my != seq) return;
            List<RowAdapter.Row> rows = new ArrayList<>();
            for (Order o : list) rows.add(RowAdapter.orderRow(o));
            adapter.setRows(rows);
            tvEmpty.setText(scope == 0 && shifts.getOpenShift() == null
                    ? "لا توجد وردية مفتوحة" : "لا توجد طلبات");
            tvEmpty.setVisibility(rows.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
        });
    }
}

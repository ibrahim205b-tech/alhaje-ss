package iq.haji.cashier.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import iq.haji.cashier.R;
import iq.haji.cashier.model.Order;
import iq.haji.cashier.model.OrderStatus;
import iq.haji.cashier.model.OrderType;
import iq.haji.cashier.util.Fmt;

/** قائمة صفوف عامة (طلبات، أصناف، أقسام، ورديات، تقارير). */
public class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {

    public static class Row {
        public String title, subtitle, trailing, badge;
        public int badgeColor = R.color.text_secondary;
        public int trailingColor = R.color.text_primary;
        public Object tag;

        public Row(String title, String subtitle, String trailing, Object tag) {
            this.title = title;
            this.subtitle = subtitle;
            this.trailing = trailing;
            this.tag = tag;
        }

        public Row badge(String text, int colorRes) {
            this.badge = text;
            this.badgeColor = colorRes;
            return this;
        }
    }

    public interface Click {
        void onClick(Row r);
    }

    private final List<Row> rows = new ArrayList<>();
    private final Click click;

    public RowAdapter(Click click) {
        this.click = click;
    }

    public void setRows(List<Row> list) {
        rows.clear();
        rows.addAll(list);
        notifyDataSetChanged();
    }

    public int size() {
        return rows.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_row, parent, false);
        final VH h = new VH(v);
        v.setOnClickListener(x -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && click != null) click.onClick(rows.get(pos));
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        bind(h.itemView, rows.get(position));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        VH(View v) {
            super(v);
        }
    }

    public static void bind(View v, Row r) {
        Context c = v.getContext();
        TextView badge = v.findViewById(R.id.tvBadge);
        TextView title = v.findViewById(R.id.tvRowTitle);
        TextView sub = v.findViewById(R.id.tvRowSub);
        TextView trail = v.findViewById(R.id.tvRowTrailing);
        title.setText(r.title);
        if (r.subtitle == null || r.subtitle.isEmpty()) {
            sub.setVisibility(View.GONE);
        } else {
            sub.setVisibility(View.VISIBLE);
            sub.setText(r.subtitle);
        }
        if (r.trailing == null) {
            trail.setVisibility(View.GONE);
        } else {
            trail.setVisibility(View.VISIBLE);
            trail.setText(r.trailing);
            trail.setTextColor(ContextCompat.getColor(c, r.trailingColor));
        }
        if (r.badge == null) {
            badge.setVisibility(View.GONE);
        } else {
            badge.setVisibility(View.VISIBLE);
            badge.setText(r.badge);
            badge.getBackground().mutate().setTint(ContextCompat.getColor(c, r.badgeColor));
        }
    }

    /** صف طلب موحّد (tag = رقم الطلب Long). */
    public static Row orderRow(Order o) {
        StringBuilder sub = new StringBuilder(Fmt.dateTime(o.createdAt));
        if (o.customerName != null && !o.customerName.isEmpty()) sub.append("  •  ").append(o.customerName);
        else if (o.phone != null && !o.phone.isEmpty()) sub.append("  •  ").append(o.phone);
        Row r = new Row("#" + o.id + "  •  " + OrderType.label(o.type), sub.toString(),
                Fmt.money(o.total), Long.valueOf(o.id));
        r.badge(OrderStatus.label(o.status), OrderStatus.colorRes(o.status));
        return r;
    }
}

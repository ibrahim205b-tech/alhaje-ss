
package iq.haji.cashier.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import iq.haji.cashier.R;
import iq.haji.cashier.model.OrderLine;
import iq.haji.cashier.util.Fmt;

public class OrderLineAdapter extends RecyclerView.Adapter<OrderLineAdapter.VH> {

    public interface Listener {
        void onPlus(OrderLine line);
        void onMinus(OrderLine line);
        void onDelete(OrderLine line);
        void onNote(OrderLine line);
    }

    private final List<OrderLine> items = new ArrayList<>();
    private final Listener listener;

    public OrderLineAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<OrderLine> lines) {
        items.clear();
        if (lines != null) {
            items.addAll(lines);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_order_line, parent, false);
        return new VH(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        OrderLine line = items.get(position);

        holder.name.setText(line.name == null ? "" : line.name);
        holder.unit.setText(Fmt.money(line.unitPrice));
        holder.qty.setText(String.valueOf(line.qty));
        holder.total.setText(Fmt.money(line.total()));

        String note = line.note == null || line.note.trim().isEmpty()
                ? "＋ ملاحظة"
                : "ملاحظة: " + line.note;
        holder.note.setText(note);

        holder.plus.setOnClickListener(v -> listener.onPlus(line));
        holder.minus.setOnClickListener(v -> listener.onMinus(line));
        holder.delete.setOnClickListener(v -> listener.onDelete(line));
        holder.note.setOnClickListener(v -> listener.onNote(line));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView unit;
        final TextView note;
        final TextView qty;
        final TextView total;
        final TextView plus;
        final TextView minus;
        final TextView delete;

        VH(View view) {
            super(view);
            name = view.findViewById(R.id.tvLineName);
            unit = view.findViewById(R.id.tvLineUnit);
            note = view.findViewById(R.id.tvLineNote);
            qty = view.findViewById(R.id.tvQty);
            total = view.findViewById(R.id.tvLineTotal);
            plus = view.findViewById(R.id.btnPlus);
            minus = view.findViewById(R.id.btnMinus);
            delete = view.findViewById(R.id.btnDelete);
        }
    }
}

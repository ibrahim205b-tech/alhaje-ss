package iq.haji.cashier.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import iq.haji.cashier.R;
import iq.haji.cashier.model.Product;
import iq.haji.cashier.util.Fmt;
import iq.haji.cashier.util.ImageCache;

/** بطاقات الأصناف في شاشة الكاشير. */
public class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.VH> {

    public interface Listener {
        void onProductClick(Product p);
    }

    private final List<Product> items = new ArrayList<>();
    private final Listener listener;

    public ProductAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setItems(List<Product> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_product, parent, false);
        final VH h = new VH(v);
        v.setOnClickListener(x -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onProductClick(items.get(pos));
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Product p = items.get(position);
        h.name.setText(p.name);
        h.price.setText(Fmt.money(p.price));
        ImageCache.load(h.img, p.imageUri);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView img;
        final TextView name;
        final TextView price;

        VH(View v) {
            super(v);
            img = v.findViewById(R.id.imgProduct);
            name = v.findViewById(R.id.tvName);
            price = v.findViewById(R.id.tvPrice);
        }
    }
}

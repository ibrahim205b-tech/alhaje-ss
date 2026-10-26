package iq.haji.cashier.ui;

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
import iq.haji.cashier.model.Category;

/** شريط الأقسام. العنصر الأول «الكل» برقم 0. */
public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.VH> {

    public interface Listener {
        void onCategorySelected(long categoryId);
    }

    private final List<Category> items = new ArrayList<>();
    private final Listener listener;
    private long selectedId = 0;

    public CategoryAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setCategories(List<Category> cats, long selectedId) {
        items.clear();
        items.add(new Category(0, "الكل", 0, true));
        items.addAll(cats);
        this.selectedId = selectedId;
        notifyDataSetChanged();
    }

    public void setSelected(long id) {
        selectedId = id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_category, parent, false);
        final VH h = new VH((TextView) v);
        h.text.setOnClickListener(x -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onCategorySelected(items.get(pos).id);
        });
        return h;
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Category c = items.get(position);
        boolean sel = c.id == selectedId;
        h.text.setText(c.name);
        h.text.setSelected(sel);
        h.text.setTextColor(ContextCompat.getColor(h.text.getContext(),
                sel ? R.color.brand_navy : R.color.text_primary));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView text;

        VH(TextView t) {
            super(t);
            text = t;
        }
    }
}

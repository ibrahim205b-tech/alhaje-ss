package iq.haji.cashier;

import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import iq.haji.cashier.data.CatalogRepository;
import iq.haji.cashier.model.Category;
import iq.haji.cashier.model.Product;
import iq.haji.cashier.ui.RowAdapter;
import iq.haji.cashier.util.AppException;
import iq.haji.cashier.util.Fmt;
import iq.haji.cashier.util.ImageCache;
import iq.haji.cashier.util.ImageStore;
import iq.haji.cashier.util.PinDialog;
import iq.haji.cashier.util.Ui;
import iq.haji.cashier.util.UiKit;

public class ProductsActivity extends PageActivity {
    private CatalogRepository catalog;
    private RowAdapter productAdapter, catAdapter;
    private EditText etSearch;
    private TextView tvEmpty;
    private List<Category> cats = new ArrayList<>();
    private long selectedCat = 0;
    private String query = "";

    private ImageView dlgImage;
    private String dlgImagePath;

    private final ActivityResultLauncher<String> imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null || dlgImage == null) return;
                final ImageView target = dlgImage;
                this.<String>bg(() -> ImageStore.importImage(this, uri), path -> {
                    dlgImagePath = path;
                    ImageCache.load(target, path);
                });
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setPageTitle("إدارة الأصناف والأسعار");
        catalog = new CatalogRepository(this);
        addAction("＋ صنف جديد", () -> showProductDialog(null));
        addAction("＋ قسم جديد", this::showAddCategory);

        LinearLayout h = UiKit.row(this);
        content.addView(h, new FrameLayout.LayoutParams(UiKit.MATCH, UiKit.MATCH));

        // الأصناف (يمين)
        LinearLayout products = UiKit.vertical(this);
        h.addView(products, new LinearLayout.LayoutParams(0, UiKit.MATCH, 5f));
        etSearch = Ui.editText(this, "🔍  ابحث عن صنف...", InputType.TYPE_CLASS_TEXT, "");
        products.addView(etSearch, new LinearLayout.LayoutParams(UiKit.MATCH, UiKit.WRAP));
        FrameLayout frame = new FrameLayout(this);
        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        productAdapter = new RowAdapter(r -> showProductDialog((Product) r.tag));
        rv.setAdapter(productAdapter);
        frame.addView(rv, new FrameLayout.LayoutParams(UiKit.MATCH, UiKit.MATCH));
        tvEmpty = UiKit.text(this, "لا توجد أصناف", 18, R.color.text_secondary, false);
        frame.addView(tvEmpty, new FrameLayout.LayoutParams(UiKit.WRAP, UiKit.WRAP, Gravity.CENTER));
        products.addView(frame, UiKit.lp(this, UiKit.MATCH, 0, 8));
        ((LinearLayout.LayoutParams) frame.getLayoutParams()).weight = 1f;

        // الأقسام (يسار)
        LinearLayout catCard = UiKit.card(this);
        h.addView(catCard, UiKit.weight(this, 3, 12));
        catCard.addView(UiKit.title(this, "الأقسام"));
        catCard.addView(UiKit.hint(this, "اضغط قسماً لتصفية الأصناف أو تعديله"));
        RecyclerView rvc = new RecyclerView(this);
        rvc.setLayoutManager(new LinearLayoutManager(this));
        catAdapter = new RowAdapter(r -> onCategoryClick(r.tag));
        rvc.setAdapter(catAdapter);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(UiKit.MATCH, 0, 1f);
        cp.topMargin = Ui.dp(this, 8);
        catCard.addView(rvc, cp);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                reload();
            }
        });
        reload();
    }

    private void reload() {
        cats = catalog.getCategories(false);
        List<Product> all = catalog.getProducts(0, null, false);
        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (Category c : cats) names.put(c.id, c.name);
        for (Product p : all) {
            Integer n = counts.get(p.categoryId);
            counts.put(p.categoryId, n == null ? 1 : n + 1);
        }
        if (selectedCat != 0 && !names.containsKey(selectedCat)) selectedCat = 0;

        List<RowAdapter.Row> cr = new ArrayList<>();
        cr.add(new RowAdapter.Row("كل الأقسام", all.size() + " صنف", selectedCat == 0 ? "✓" : null, Long.valueOf(0L)));
        for (Category c : cats) {
            Integer n = counts.get(c.id);
            RowAdapter.Row r = new RowAdapter.Row(c.name, (n == null ? 0 : n) + " صنف",
                    selectedCat == c.id ? "✓" : null, c);
            if (!c.active) r.badge("مخفي", R.color.warning);
            cr.add(r);
        }
        catAdapter.setRows(cr);

        List<RowAdapter.Row> pr = new ArrayList<>();
        for (Product p : catalog.getProducts(selectedCat, query, false)) {
            RowAdapter.Row r = new RowAdapter.Row(p.name, names.get(p.categoryId), Fmt.money(p.price), p);
            if (!p.active) r.badge("مخفي", R.color.warning);
            pr.add(r);
        }
        productAdapter.setRows(pr);
        tvEmpty.setVisibility(pr.isEmpty() ? View.VISIBLE : View.GONE);
    }

    // ======================= الأقسام =======================

    private void onCategoryClick(Object tag) {
        if (tag instanceof Long) {
            selectedCat = (Long) tag;
            reload();
            return;
        }
        final Category c = (Category) tag;
        String[] items = {"عرض أصنافه فقط", "إعادة تسمية", c.active ? "إخفاء القسم من الكاشير" : "إظهار القسم في الكاشير",
                "حذف القسم"};
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(c.name)
                .setItems(items, (x, which) -> {
                    if (which == 0) {
                        selectedCat = c.id;
                        reload();
                    } else if (which == 1) {
                        showRenameCategory(c);
                    } else if (which == 2) {
                        catalog.setCategoryActive(c.id, !c.active);
                        reload();
                    } else {
                        confirmDeleteCategory(c);
                    }
                })
                .setNegativeButton(R.string.close, null)
                .create();
        Ui.showRtl(d);
    }

    private void showAddCategory() {
        final EditText et = Ui.editText(this, "اسم القسم", InputType.TYPE_CLASS_TEXT, "");
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("قسم جديد")
                .setView(Ui.wrap(this, et))
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                catalog.addCategory(et.getText().toString());
                d.dismiss();
                reload();
            } catch (AppException e) {
                et.setError(e.getMessage());
            }
        }));
        Ui.showRtl(d);
    }

    private void showRenameCategory(final Category c) {
        final EditText et = Ui.editText(this, "اسم القسم", InputType.TYPE_CLASS_TEXT, c.name);
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("إعادة تسمية القسم")
                .setView(Ui.wrap(this, et))
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                catalog.renameCategory(c.id, et.getText().toString());
                d.dismiss();
                reload();
            } catch (AppException e) {
                et.setError(e.getMessage());
            }
        }));
        Ui.showRtl(d);
    }

    private void confirmDeleteCategory(final Category c) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("حذف القسم")
                .setMessage("حذف القسم «" + c.name + "» نهائياً؟ لا يُحذف إلا إذا كان فارغاً من الأصناف.")
                .setPositiveButton(R.string.delete, (x, w) -> PinDialog.require(this, () -> {
                    try {
                        catalog.deleteCategory(c.id);
                        reload();
                    } catch (AppException e) {
                        Ui.toast(this, e.getMessage());
                    }
                }))
                .setNegativeButton(R.string.cancel, null)
                .create();
        Ui.showRtl(d);
    }

    // ======================= الأصناف =======================

    private void showProductDialog(final Product existing) {
        if (cats.isEmpty()) {
            Ui.toast(this, "أضف قسماً أولاً");
            return;
        }
        dlgImagePath = existing == null ? null : existing.imageUri;

        LinearLayout col = UiKit.vertical(this);
        int pad = Ui.dp(this, 20);
        col.setPadding(pad, Ui.dp(this, 8), pad, 0);

        final EditText etName = Ui.editText(this, "اسم الصنف", InputType.TYPE_CLASS_TEXT,
                existing == null ? "" : existing.name);
        col.addView(UiKit.label(this, "اسم الصنف"));
        col.addView(etName);

        final EditText etPrice = Ui.editText(this, "السعر بالدينار", InputType.TYPE_CLASS_NUMBER,
                existing == null ? "" : String.valueOf(existing.price));
        col.addView(UiKit.label(this, "السعر (د.ع)"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        col.addView(etPrice);

        col.addView(UiKit.label(this, "القسم"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 12));
        final Spinner sp = new Spinner(this);
        List<String> names = new ArrayList<>();
        int sel = 0;
        long want = existing != null ? existing.categoryId : selectedCat;
        for (int i = 0; i < cats.size(); i++) {
            names.add(cats.get(i).name + (cats.get(i).active ? "" : " (مخفي)"));
            if (cats.get(i).id == want) sel = i;
        }
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        sp.setSelection(sel);
        col.addView(sp);

        final SwitchCompat swActive = UiKit.toggle(this, "ظاهر في شاشة الكاشير",
                existing == null || existing.active);
        col.addView(swActive, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 8));

        col.addView(UiKit.label(this, "صورة (اختيارية)"), UiKit.lp(this, UiKit.WRAP, UiKit.WRAP, 8));
        LinearLayout imgRow = UiKit.row(this);
        imgRow.setGravity(Gravity.CENTER_VERTICAL);
        dlgImage = new ImageView(this);
        dlgImage.setBackgroundResource(R.drawable.bg_product_image);
        ImageCache.load(dlgImage, dlgImagePath);
        imgRow.addView(dlgImage, new LinearLayout.LayoutParams(Ui.dp(this, 90), Ui.dp(this, 90)));
        TextView pick = UiKit.button(this, "اختيار صورة", false);
        pick.setOnClickListener(v -> imagePicker.launch("image/*"));
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(UiKit.WRAP, UiKit.WRAP);
        pp.setMarginStart(Ui.dp(this, 12));
        imgRow.addView(pick, pp);
        TextView clear = UiKit.button(this, "إزالة", false);
        clear.setOnClickListener(v -> {
            dlgImagePath = null;
            ImageCache.load(dlgImage, null);
        });
        imgRow.addView(clear, pp);
        col.addView(imgRow, UiKit.lp(this, UiKit.MATCH, UiKit.WRAP, 4));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "صنف جديد" : "تعديل الصنف")
                .setView(sv)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null);
        if (existing != null) b.setNeutralButton(R.string.delete, null);
        final AlertDialog d = b.create();
        d.setOnDismissListener(x -> dlgImage = null);
        d.setOnShowListener(x -> {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                long price = Fmt.parseMoney(etPrice.getText().toString());
                if (price <= 0) {
                    etPrice.setError("أدخل سعراً صحيحاً أكبر من صفر");
                    return;
                }
                final Product np = new Product();
                np.id = existing == null ? 0 : existing.id;
                np.categoryId = cats.get(sp.getSelectedItemPosition()).id;
                np.name = etName.getText().toString();
                np.price = price;
                np.imageUri = dlgImagePath;
                np.active = swActive.isChecked();
                Runnable save = () -> {
                    try {
                        catalog.saveProduct(np);
                        d.dismiss();
                        reload();
                        Ui.toast(this, "تم الحفظ");
                    } catch (AppException e) {
                        Ui.toast(this, e.getMessage());
                    }
                };
                if (existing != null && existing.price != price) PinDialog.require(this, save);
                else save.run();
            });
            if (existing != null) {
                d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> confirmDeleteProduct(existing, d));
            }
        });
        Ui.showRtl(d);
    }

    private void confirmDeleteProduct(final Product p, final AlertDialog parent) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("حذف الصنف")
                .setMessage("حذف «" + p.name + "» نهائياً؟ الطلبات القديمة لا تتأثر. "
                        + "إن أردت إيقافه مؤقتاً فاستعمل خيار الإخفاء بدلاً من الحذف.")
                .setPositiveButton(R.string.delete, (x, w) -> PinDialog.require(this, () -> {
                    catalog.deleteProduct(p.id);
                    parent.dismiss();
                    reload();
                }))
                .setNegativeButton(R.string.cancel, null)
                .create();
        Ui.showRtl(d);
    }
}

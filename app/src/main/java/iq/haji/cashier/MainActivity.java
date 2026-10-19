package iq.haji.cashier;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

import iq.haji.cashier.data.AppSettings;
import iq.haji.cashier.data.CatalogRepository;
import iq.haji.cashier.data.OrderRepository;
import iq.haji.cashier.data.ShiftRepository;
import iq.haji.cashier.model.Category;
import iq.haji.cashier.model.Order;
import iq.haji.cashier.model.OrderLine;
import iq.haji.cashier.model.OrderStatus;
import iq.haji.cashier.model.OrderType;
import iq.haji.cashier.model.Product;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.print.PrinterManager;
import iq.haji.cashier.print.ReceiptPrinter;
import iq.haji.cashier.ui.CategoryAdapter;
import iq.haji.cashier.ui.OrderLineAdapter;
import iq.haji.cashier.ui.ProductAdapter;
import iq.haji.cashier.util.AppException;
import iq.haji.cashier.util.ArabicText;
import iq.haji.cashier.util.Fmt;
import iq.haji.cashier.util.PinDialog;
import iq.haji.cashier.util.Ui;

/**
 * شاشة الكاشير. الطلب الجاري يُحفظ كمسودة تلقائياً. زر «طباعة الفاتورة» هو الوحيد الذي ينشئ رقم الطلب،
 * وبعده يُقفل الطلب حتى تنجح الطباعة أو يُلغى، فلا تتكرر الفاتورة عند إعادة المحاولة.
 */
public class MainActivity extends BaseActivity implements PrinterManager.StateListener {

    private static final String[] TYPE_CODES = {OrderType.TAKEAWAY, OrderType.DELIVERY, OrderType.DINE_IN};

    private CatalogRepository catalog;
    private OrderRepository orders;
    private ShiftRepository shifts;
    private AppSettings settings;
    private ReceiptPrinter printer;

    private Order current;
    private long selectedCategory = 0;
    private String query = "";
    private List<Product> allProducts = new ArrayList<>();
    private boolean firstRunShown = false;
    private boolean printing = false;

    private TextView chipShift, chipPrinter, tvOrderNo, tvOrderTime, tvLockedBanner, tvDetailsSummary;
    private TextView tvEmptyOrder, tvNoProducts, tvSubtotal, tvDiscount, tvDelivery, tvTotal, btnPrint;
    private TextView btnCancelOrder, btnNewOrder;
    private View rowDiscount, rowDelivery;
    private final TextView[] typeViews = new TextView[3];
    private RecyclerView rvLines;

    private ProductAdapter productAdapter;
    private CategoryAdapter categoryAdapter;
    private OrderLineAdapter lineAdapter;

    // ======================= دورة الحياة =======================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        catalog = new CatalogRepository(this);
        orders = new OrderRepository(this);
        shifts = new ShiftRepository(this);
        settings = new AppSettings(this);
        printer = new ReceiptPrinter(this);

        bindViews();
        setupLists();
        setupActions();
        loadInitialOrder();
    }

    @Override
    protected void onStart() {
        super.onStart();
        PrinterManager.get(this).addListener(this);
    }

    @Override
    protected void onStop() {
        PrinterManager.get(this).removeListener(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncCurrentWithDb();
        reloadCatalog();
        refreshOrderUi();
        refreshChips();
        maybeShowFirstRun();
    }

    @Override
    public void onStateChanged(PrinterManager.State state, String message) {
        refreshPrinterChip();
    }

    // ======================= تهيئة الواجهة =======================

    private void bindViews() {
        chipShift = findViewById(R.id.chipShift);
        chipPrinter = findViewById(R.id.chipPrinter);
        tvOrderNo = findViewById(R.id.tvOrderNo);
        tvOrderTime = findViewById(R.id.tvOrderTime);
        tvLockedBanner = findViewById(R.id.tvLockedBanner);
        tvDetailsSummary = findViewById(R.id.tvDetailsSummary);
        tvEmptyOrder = findViewById(R.id.tvEmptyOrder);
        tvNoProducts = findViewById(R.id.tvNoProducts);
        tvSubtotal = findViewById(R.id.tvSubtotal);
        tvDiscount = findViewById(R.id.tvDiscount);
        tvDelivery = findViewById(R.id.tvDelivery);
        tvTotal = findViewById(R.id.tvTotal);
        btnPrint = findViewById(R.id.btnPrint);
        btnCancelOrder = findViewById(R.id.btnCancelOrder);
        btnNewOrder = findViewById(R.id.btnNewOrder);
        rowDiscount = findViewById(R.id.rowDiscount);
        rowDelivery = findViewById(R.id.rowDelivery);
        rvLines = findViewById(R.id.rvLines);
        typeViews[0] = findViewById(R.id.tvTypeTakeaway);
        typeViews[1] = findViewById(R.id.tvTypeDelivery);
        typeViews[2] = findViewById(R.id.tvTypeDineIn);
    }

    private void setupLists() {
        // عدد أعمدة الأصناف حسب عرض الشاشة (≈ 60% من العرض، بطاقة ≈ 170dp)
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int span = Math.max(2, (int) (widthDp * 0.6f - 24) / 170);

        RecyclerView rvProducts = findViewById(R.id.rvProducts);
        rvProducts.setLayoutManager(new GridLayoutManager(this, span));
        productAdapter = new ProductAdapter(this::addProduct);
        rvProducts.setAdapter(productAdapter);

        RecyclerView rvCategories = findViewById(R.id.rvCategories);
        rvCategories.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        categoryAdapter = new CategoryAdapter(id -> {
            selectedCategory = id;
            categoryAdapter.setSelected(id);
            applyFilter();
        });
        rvCategories.setAdapter(categoryAdapter);

        rvLines.setLayoutManager(new LinearLayoutManager(this));
        lineAdapter = new OrderLineAdapter(new OrderLineAdapter.Listener() {
            @Override
            public void onPlus(OrderLine l) {
                changeQty(l, +1);
            }

            @Override
            public void onMinus(OrderLine l) {
                changeQty(l, -1);
            }

            @Override
            public void onDelete(OrderLine l) {
                removeLine(l);
            }

            @Override
            public void onNote(OrderLine l) {
                editNote(l);
            }
        });
        rvLines.setAdapter(lineAdapter);
    }

    private void setupActions() {
        findViewById(R.id.navOrders).setOnClickListener(v -> open(OrdersActivity.class));
        findViewById(R.id.navProducts).setOnClickListener(v -> open(ProductsActivity.class));
        findViewById(R.id.navReports).setOnClickListener(v -> open(ReportsActivity.class));
        findViewById(R.id.navShift).setOnClickListener(v -> open(ShiftActivity.class));
        findViewById(R.id.navPrinter).setOnClickListener(v -> open(PrinterSettingsActivity.class));
        findViewById(R.id.navSettings).setOnClickListener(v -> open(SettingsActivity.class));
        chipShift.setOnClickListener(v -> open(ShiftActivity.class));
        chipPrinter.setOnClickListener(v -> open(PrinterSettingsActivity.class));

        for (int i = 0; i < typeViews.length; i++) {
            final String code = TYPE_CODES[i];
            typeViews[i].setOnClickListener(v -> setType(code));
        }

        EditText etSearch = findViewById(R.id.etSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                applyFilter();
            }
        });
        etSearch.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                v.clearFocus();
                return true;
            }
            return false;
        });

        findViewById(R.id.btnDetails).setOnClickListener(v -> showDetailsDialog());
        btnCancelOrder.setOnClickListener(v -> onCancelOrder());
        btnNewOrder.setOnClickListener(v -> onNewOrder());
        btnPrint.setOnClickListener(v -> onPrint());
    }

    private void open(Class<?> cls) {
        startActivity(new Intent(this, cls));
    }

    // ======================= حالة الطلب =======================

    private boolean locked() {
        return current != null && current.id != 0;
    }

    private void loadInitialOrder() {
        List<Order> pending = orders.listPendingPrint();
        if (!pending.isEmpty()) {
            Order o = orders.getOrder(pending.get(0).id);
            if (o != null) {
                current = o;
                if (pending.size() > 1) {
                    Ui.toast(this, "يوجد " + pending.size() + " طلبات بانتظار الطباعة. الأحدث معروض هنا والبقية في شاشة الطلبات.");
                }
                return;
            }
        }
        Order draft = orders.loadDraft();
        if (draft != null) {
            current = draft;
            if (current.createdAt == 0) current.createdAt = System.currentTimeMillis();
            normalizeType();
        } else {
            newOrder();
        }
    }

    private void newOrder() {
        current = new Order();
        current.createdAt = System.currentTimeMillis();
        current.type = TYPE_CODES[0];
        normalizeType();
        if (OrderType.DELIVERY.equals(current.type)) {
            current.deliveryFee = settings.getLong(AppSettings.DEFAULT_DELIVERY_FEE, 0);
        }
    }

    private void resetOrder() {
        orders.clearDraft();
        newOrder();
        refreshOrderUi();
    }

    /** إذا تغيّر الطلب المقفل من شاشة أخرى (طُبع أو أُلغي) نبدأ طلباً جديداً. */
    private void syncCurrentWithDb() {
        if (printing || current == null || current.id == 0) return;
        Order fresh = orders.getOrder(current.id);
        if (fresh == null || !OrderStatus.PENDING_PRINT.equals(fresh.status)) newOrder();
        else current = fresh;
    }

    private boolean typeEnabled(String t) {
        if (OrderType.DELIVERY.equals(t)) return settings.getBool(AppSettings.ENABLE_DELIVERY, true);
        if (OrderType.DINE_IN.equals(t)) return settings.getBool(AppSettings.ENABLE_DINE_IN, true);
        return settings.getBool(AppSettings.ENABLE_TAKEAWAY, true);
    }

    private void normalizeType() {
        if (typeEnabled(current.type)) return;
        for (String t : TYPE_CODES) {
            if (typeEnabled(t)) {
                current.type = t;
                return;
            }
        }
    }

    private void persist() {
        current.recalc();
        if (!locked()) orders.saveDraft(current);
        refreshOrderUi();
    }

    private void toastLocked() {
        Ui.toast(this, "الطلب رقم " + current.id + " محفوظ بانتظار الطباعة. أعد المحاولة أو ألغِه.");
    }

    private void setType(String t) {
        if (printing) return;
        if (locked()) {
            toastLocked();
            return;
        }
        current.type = t;
        if (OrderType.DELIVERY.equals(t) && current.deliveryFee == 0) {
            current.deliveryFee = settings.getLong(AppSettings.DEFAULT_DELIVERY_FEE, 0);
        }
        persist();
    }

    // ======================= الأصناف =======================

    private void reloadCatalog() {
        List<Category> cats = catalog.getCategories(true);
        boolean found = selectedCategory == 0;
        for (Category c : cats) if (c.id == selectedCategory) found = true;
        if (!found) selectedCategory = 0;
        categoryAdapter.setCategories(cats, selectedCategory);
        allProducts = catalog.getProducts(0, null, true);
        applyFilter();
    }

    private void applyFilter() {
        String q = ArabicText.normalize(query);
        List<Product> out = new ArrayList<>();
        for (Product p : allProducts) {
            if (selectedCategory > 0 && p.categoryId != selectedCategory) continue;
            if (!q.isEmpty() && !ArabicText.normalize(p.name).contains(q)) continue;
            out.add(p);
        }
        productAdapter.setItems(out);
        if (out.isEmpty()) {
            tvNoProducts.setText(allProducts.isEmpty()
                    ? "لا توجد أصناف. أضف أصنافاً من شاشة «الأصناف»."
                    : "لا توجد أصناف مطابقة");
            tvNoProducts.setVisibility(View.VISIBLE);
        } else {
            tvNoProducts.setVisibility(View.GONE);
        }
    }

    private void addProduct(Product p) {
        if (printing) return;
        if (locked()) {
            toastLocked();
            return;
        }
        for (OrderLine l : current.lines) {
            if (l.productId == p.id && (l.note == null || l.note.trim().isEmpty())) {
                if (l.qty < 999) l.qty++;
                persist();
                return;
            }
        }
        OrderLine l = new OrderLine();
        l.productId = p.id;
        l.name = p.name;
        l.unitPrice = p.price;
        l.qty = 1;
        current.lines.add(l);
        persist();
        rvLines.scrollToPosition(current.lines.size() - 1);
    }

    private void changeQty(OrderLine l, int delta) {
        if (printing) return;
        if (locked()) {
            toastLocked();
            return;
        }
        int q = Math.max(1, Math.min(999, l.qty + delta));
        l.qty = q;
        persist();
    }

    private void removeLine(OrderLine l) {
        if (printing) return;
        if (locked()) {
            toastLocked();
            return;
        }
        current.lines.remove(l);
        persist();
    }

    private void editNote(final OrderLine l) {
        if (printing) return;
        if (locked()) {
            toastLocked();
            return;
        }
        final EditText et = Ui.editText(this, "مثال: بدون بصل، حجم كبير، زيادة جبن",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                l.note == null ? "" : l.note);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("ملاحظة على: " + l.name)
                .setView(Ui.wrap(this, et))
                .setPositiveButton(R.string.save, (dd, w) -> {
                    String n = et.getText().toString().trim();
                    if (n.length() > 80) n = n.substring(0, 80);
                    l.note = n;
                    persist();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        Ui.showRtl(d);
    }

    // ======================= بيانات الطلب =======================

    private void showDetailsDialog() {
        if (printing) return;
        if (locked()) {
            Ui.toast(this, "الطلب محفوظ ولا يمكن تعديل بياناته");
            return;
        }
        final boolean delivery = OrderType.DELIVERY.equals(current.type);
        final boolean allowDiscount = settings.getBool(AppSettings.ALLOW_DISCOUNT, true);

        View v = getLayoutInflater().inflate(R.layout.dialog_order_details, null);
        v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        final EditText etName = v.findViewById(R.id.etCustomerName);
        final EditText etPhone = v.findViewById(R.id.etPhone);
        final EditText etAddress = v.findViewById(R.id.etAddress);
        final EditText etDriver = v.findViewById(R.id.etDriver);
        final EditText etFee = v.findViewById(R.id.etDeliveryFee);
        final EditText etDisc = v.findViewById(R.id.etDiscount);
        final EditText etNotes = v.findViewById(R.id.etNotes);
        v.findViewById(R.id.groupDelivery).setVisibility(delivery ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.groupDiscount).setVisibility(allowDiscount ? View.VISIBLE : View.GONE);

        etName.setText(nz(current.customerName));
        etPhone.setText(nz(current.phone));
        etAddress.setText(nz(current.address));
        etDriver.setText(nz(current.driver));
        if (current.deliveryFee > 0) etFee.setText(String.valueOf(current.deliveryFee));
        if (current.discount > 0) etDisc.setText(String.valueOf(current.discount));
        etNotes.setText(nz(current.notes));

        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("بيانات الطلب")
                .setView(v)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            long fee = 0;
            long disc = 0;
            if (delivery) {
                String s = etFee.getText().toString().trim();
                if (!s.isEmpty()) {
                    fee = Fmt.parseMoney(s);
                    if (fee < 0) {
                        etFee.setError("مبلغ غير صحيح");
                        return;
                    }
                }
            }
            if (allowDiscount) {
                String s = etDisc.getText().toString().trim();
                if (!s.isEmpty()) {
                    disc = Fmt.parseMoney(s);
                    if (disc < 0) {
                        etDisc.setError("مبلغ غير صحيح");
                        return;
                    }
                    if (disc > current.subtotal) {
                        etDisc.setError("الخصم أكبر من مجموع الطلب");
                        return;
                    }
                }
            }
            current.customerName = etName.getText().toString().trim();
            current.phone = Fmt.normalizeDigits(etPhone.getText().toString());
            current.notes = etNotes.getText().toString().trim();
            if (delivery) {
                current.address = etAddress.getText().toString().trim();
                current.driver = etDriver.getText().toString().trim();
                current.deliveryFee = fee;
            }
            if (allowDiscount) current.discount = disc;
            d.dismiss();
            persist();
        }));
        Ui.showRtl(d);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    // ======================= طلب جديد / إلغاء =======================

    private void onNewOrder() {
        if (printing) return;
        if (locked()) {
            toastLocked();
            return;
        }
        if (current.lines.isEmpty()) {
            resetOrder();
            return;
        }
        confirm("طلب جديد", "سيتم تجاهل الطلب الحالي غير المطبوع. متابعة؟", "تجاهل وابدأ طلباً جديداً",
                this::resetOrder);
    }

    private void onCancelOrder() {
        if (printing) return;
        if (!locked()) {
            if (current.lines.isEmpty()) {
                Ui.toast(this, "الطلب فارغ");
                return;
            }
            confirm("إلغاء الطلب", "حذف جميع أصناف الطلب الحالي؟ لم يُحفظ بعد ولن يظهر في السجل.", "حذف الطلب",
                    this::resetOrder);
            return;
        }
        final long id = current.id;
        final EditText reason = Ui.editText(this, "سبب الإلغاء (اختياري)", InputType.TYPE_CLASS_TEXT, "");
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("إلغاء الطلب رقم " + id)
                .setMessage("سيُسجَّل الطلب «ملغى» ويبقى في سجل الطلبات ولا يُحتسب في المبيعات.")
                .setView(Ui.wrap(this, reason))
                .setPositiveButton("إلغاء الطلب", (dd, w) -> {
                    final String why = reason.getText().toString().trim();
                    PinDialog.require(this, () -> doCancelSaved(id, why));
                })
                .setNegativeButton(R.string.back, null)
                .create();
        Ui.showRtl(d);
    }

    private void doCancelSaved(long id, String why) {
        try {
            orders.cancelOrder(id, why);
        } catch (AppException e) {
            Ui.toast(this, e.getMessage());
            return;
        }
        newOrder();
        refreshOrderUi();
        Ui.toast(this, "أُلغي الطلب رقم " + id);
    }

    private void confirm(String title, String msg, String positive, final Runnable onYes) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton(positive, (dd, w) -> onYes.run())
                .setNegativeButton(R.string.back, null)
                .create();
        Ui.showRtl(d);
    }

    // ======================= الطباعة =======================

    private void onPrint() {
        if (printing || ReceiptPrinter.isBusy()) return;

        if (!locked()) {
            if (current.lines.isEmpty()) {
                Ui.toast(this, "الطلب فارغ. أضف صنفاً أولاً.");
                return;
            }
            if (shifts.getOpenShift() == null) {
                showNoShiftDialog();
                return;
            }
            if (OrderType.DELIVERY.equals(current.type) && isBlank(current.address)) {
                Ui.toast(this, "أدخل عنوان التوصيل لطلب الدليفري");
                showDetailsDialog();
                return;
            }
        }
        if (!PrinterManager.get(this).hasSelectedPrinter()) {
            showNoPrinterDialog();
            return;
        }
        setBusy(true);
        printer.printOrder(current, this::onPrintResult);
    }

    private void setBusy(boolean b) {
        printing = b;
        refreshOrderUi();
    }

    private void onPrintResult(ReceiptPrinter.Result r) {
        printing = false;
        if (isFinishing() || isDestroyed()) return;

        if (r.success) {
            ensurePrintedState(r.orderId);
            newOrder();
            refreshOrderUi();
            showSuccess(r.message);
            return;
        }
        if (r.orderSaved && r.orderId != 0) {
            Order fresh = orders.getOrder(r.orderId);
            if (fresh != null && OrderStatus.PENDING_PRINT.equals(fresh.status)) {
                current = fresh;
            } else if (fresh != null) {
                newOrder(); // مثلاً أُلغي من شاشة أخرى
            }
        }
        refreshOrderUi();
        showFailure(r);
    }

    /** إذا نجحت الطباعة لكن تحديث الحالة تعثّر، نحاول مرة أخرى بصمت. */
    private void ensurePrintedState(long orderId) {
        if (orderId == 0) return;
        Order o = orders.getOrder(orderId);
        if (o != null && OrderStatus.PENDING_PRINT.equals(o.status)) {
            try {
                orders.markPrinted(orderId);
            } catch (RuntimeException ignored) {
                // الرسالة المعروضة تحذّر المستخدم من إعادة الطباعة
            }
        }
    }

    private void showSuccess(String msg) {
        Snackbar sb = Snackbar.make(findViewById(R.id.root), msg, Snackbar.LENGTH_LONG);
        sb.setDuration(9000);
        sb.setAction(R.string.ok, v -> {
        });
        View tv = sb.getView().findViewById(com.google.android.material.R.id.snackbar_text);
        if (tv instanceof TextView) ((TextView) tv).setMaxLines(6);
        sb.show();
    }

    private void showFailure(ReceiptPrinter.Result r) {
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("لم تتم الطباعة")
                .setMessage(r.message)
                .setNeutralButton("إعدادات الطابعة", (d, w) -> open(PrinterSettingsActivity.class));
        if (r.orderSaved) {
            b.setPositiveButton(R.string.retry, (d, w) -> onPrint());
            b.setNegativeButton(R.string.close, null);
        } else {
            b.setPositiveButton(R.string.ok, null);
        }
        Ui.showRtl(b.create());
    }

    private void showNoShiftDialog() {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("لا توجد وردية مفتوحة")
                .setMessage("لا يمكن حفظ الطلبات وطباعتها بدون وردية. ابدأ وردية الآن؟")
                .setPositiveButton("بدء وردية", (x, w) -> open(ShiftActivity.class))
                .setNegativeButton(R.string.cancel, null)
                .create();
        Ui.showRtl(d);
    }

    private void showNoPrinterDialog() {
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("لم تُحدد طابعة")
                .setMessage("اختر طابعة (بلوتوث أو شبكة) من إعدادات الطابعة قبل الطباعة. الطلب باقٍ كما هو ولم يُحفظ بعد.")
                .setPositiveButton("إعدادات الطابعة", (x, w) -> open(PrinterSettingsActivity.class))
                .setNegativeButton(R.string.cancel, null)
                .create();
        Ui.showRtl(d);
    }

    // ======================= المعالج الأول =======================

    private void maybeShowFirstRun() {
        if (firstRunShown || settings.getBool(AppSettings.SETUP_DONE, false)) return;
        firstRunShown = true;
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("مرحباً بك في دليفري الحجي")
                .setMessage("قبل أول استخدام:\n\n"
                        + "1) تهيئة الطابعة: سيطلب التطبيق إذن «الأجهزة القريبة» للبحث عن طابعة البلوتوث "
                        + "والاتصال بها فقط. لا يُستخدم هذا الإذن لتحديد موقعك. ويمكنك بدلاً من ذلك استخدام "
                        + "طابعة شبكية بعنوان IP.\n\n"
                        + "2) ابدأ وردية من شاشة «الوردية» قبل أول طلب.")
                .setCancelable(false)
                .setPositiveButton("تهيئة الطابعة الآن", (x, w) -> {
                    settings.setBool(AppSettings.SETUP_DONE, true);
                    Intent i = new Intent(this, PrinterSettingsActivity.class);
                    i.putExtra("first_run", true);
                    startActivity(i);
                })
                .setNegativeButton("لاحقاً", (x, w) -> settings.setBool(AppSettings.SETUP_DONE, true))
                .create();
        Ui.showRtl(d);
    }

    // ======================= تحديث الواجهة =======================

    private void refreshOrderUi() {
        current.recalc();
        boolean locked = locked();
        boolean delivery = OrderType.DELIVERY.equals(current.type);

        tvOrderNo.setText("طلب رقم " + (locked ? current.id : orders.nextOrderNumber()));
        tvOrderTime.setText(Fmt.dateTime(current.createdAt));

        for (int i = 0; i < typeViews.length; i++) {
            boolean sel = TYPE_CODES[i].equals(current.type);
            typeViews[i].setVisibility(typeEnabled(TYPE_CODES[i]) ? View.VISIBLE : View.GONE);
            typeViews[i].setSelected(sel);
            typeViews[i].setTextColor(ContextCompat.getColor(this, sel ? R.color.white : R.color.text_primary));
        }

        if (locked) {
            tvLockedBanner.setText("الطلب رقم " + current.id
                    + " محفوظ بانتظار الطباعة. اضغط «إعادة المحاولة» أو ألغِ الطلب.");
            tvLockedBanner.setVisibility(View.VISIBLE);
        } else {
            tvLockedBanner.setVisibility(View.GONE);
        }

        // ملخص بيانات الزبون
        List<String> parts = new ArrayList<>();
        if (!isBlank(current.customerName)) parts.add(current.customerName.trim());
        if (!isBlank(current.phone)) parts.add(current.phone.trim());
        if (delivery && !isBlank(current.address)) parts.add(current.address.trim());
        if (delivery && !isBlank(current.driver)) parts.add("السائق: " + current.driver.trim());
        if (parts.isEmpty()) {
            tvDetailsSummary.setText(delivery ? "أدخل عنوان التوصيل (مطلوب)" : "لا توجد بيانات زبون");
            tvDetailsSummary.setTextColor(ContextCompat.getColor(this,
                    delivery ? R.color.error : R.color.text_hint));
        } else {
            tvDetailsSummary.setText(join(parts, "  •  "));
            tvDetailsSummary.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        }

        lineAdapter.setLines(current.lines, locked);
        tvEmptyOrder.setVisibility(current.lines.isEmpty() ? View.VISIBLE : View.GONE);

        tvSubtotal.setText(Fmt.money(current.subtotal));
        rowDiscount.setVisibility(current.discount > 0 ? View.VISIBLE : View.GONE);
        tvDiscount.setText("- " + Fmt.money(current.discount));
        rowDelivery.setVisibility(delivery && current.deliveryFee > 0 ? View.VISIBLE : View.GONE);
        tvDelivery.setText(Fmt.money(current.deliveryFee));
        tvTotal.setText(Fmt.money(current.total));

        boolean canPrint = !printing && (locked || !current.lines.isEmpty());
        btnPrint.setEnabled(canPrint);
        btnPrint.setAlpha(canPrint ? 1f : 0.5f);
        btnPrint.setText(printing ? "جارٍ الطباعة..." : (locked ? "إعادة المحاولة" : "طباعة الفاتورة"));
        btnCancelOrder.setAlpha(printing ? 0.5f : 1f);
        btnNewOrder.setAlpha(printing ? 0.5f : 1f);
    }

    private static String join(List<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private void refreshChips() {
        Shift s = shifts.getOpenShift();
        if (s == null) {
            chipShift.setText("لا توجد وردية — ابدأ وردية");
            chipShift.setTextColor(ContextCompat.getColor(this, R.color.error));
        } else {
            chipShift.setText("الوردية #" + s.id + " مفتوحة");
            chipShift.setTextColor(ContextCompat.getColor(this, R.color.success));
        }
        refreshPrinterChip();
    }

    private void refreshPrinterChip() {
        PrinterManager pm = PrinterManager.get(this);
        String text;
        int color;
        if (!pm.hasSelectedPrinter()) {
            text = "الطابعة: غير محددة";
            color = R.color.warning;
        } else {
            switch (pm.getState()) {
                case CONNECTED:
                    text = "الطابعة: متصلة";
                    color = R.color.success;
                    break;
                case CONNECTING:
                    text = "الطابعة: جارٍ الاتصال...";
                    color = R.color.warning;
                    break;
                case ERROR:
                    text = "الطابعة: خطأ اتصال";
                    color = R.color.error;
                    break;
                default:
                    text = "الطابعة: تتصل عند الطباعة";
                    color = R.color.text_secondary;
            }
        }
        chipPrinter.setText(text);
        chipPrinter.setTextColor(ContextCompat.getColor(this, color));
    }
}

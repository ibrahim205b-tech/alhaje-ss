package iq.haji.cashier;

import android.app.role.RoleManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
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

public class MainActivity extends BaseActivity implements PrinterManager.StateListener {

    private static final String[] TYPE_CODES = {
            OrderType.TAKEAWAY,
            OrderType.DELIVERY,
            OrderType.DINE_IN
    };

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
    private boolean callerPollingStarted = false;
    private long lastProcessedCallTime = 0L;
    private EditText activeCallerPhoneView;

    private final Handler callerHandler = new Handler(Looper.getMainLooper());

    private final Runnable callerPollingTask = new Runnable() {
        @Override
        public void run() {
            if (!callerPollingStarted || isFinishing() || isDestroyed()) {
                return;
            }

            processLatestCallerNumber();
            callerHandler.postDelayed(this, 1000L);
        }
    };

    private static final int REQUEST_CALL_SCREENING_ROLE = 7401;

    private TextView chipShift, chipPrinter, tvOrderNo, tvOrderTime,
            tvLockedBanner, tvDetailsSummary;
    private TextView tvEmptyOrder, tvNoProducts, tvSubtotal,
            tvDiscount, tvDelivery, tvTotal, btnPrint;
    private TextView btnCancelOrder, btnNewOrder;
    private View rowDiscount, rowDelivery;
    private final TextView[] typeViews = new TextView[3];
    private RecyclerView rvLines;

    private ProductAdapter productAdapter;
    private CategoryAdapter categoryAdapter;
    private OrderLineAdapter lineAdapter;

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

        animateEntrance();

        // اترك وقت آخر مكالمة صفراً عند التشغيل حتى تتم معالجة الرقم
        // المحفوظ حتى لو وصلت المكالمة والتطبيق مغلق.
    }

    private void animateEntrance() {
        View root = findViewById(R.id.root);

        if (root == null) {
            return;
        }

        root.setAlpha(0f);
        root.setTranslationY(10f);

        root.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(320L)
                .setInterpolator(new DecelerateInterpolator())
                .start();
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
        applyPendingCallerNumber();
        reloadCatalog();
        refreshOrderUi();
        refreshChips();
        maybeShowFirstRun();
        startCallerPolling();
    }

    @Override
    protected void onPause() {
        stopCallerPolling();
        super.onPause();
    }

    private void startCallerPolling() {
        callerPollingStarted = true;
        callerHandler.removeCallbacks(callerPollingTask);
        processLatestCallerNumber();
        callerHandler.postDelayed(callerPollingTask, 1000L);
    }

    private void stopCallerPolling() {
        callerPollingStarted = false;
        callerHandler.removeCallbacks(callerPollingTask);
    }

    private void processLatestCallerNumber() {
        android.content.SharedPreferences prefs = getSharedPreferences(
                "haji_call_data", MODE_PRIVATE);

        long callTime = prefs.getLong("latest_call_time", 0L);

        if (callTime <= 0L || callTime <= lastProcessedCallTime) {
            return;
        }

        String phone = prefs.getString("latest_number", "");
        lastProcessedCallTime = callTime;

        if (phone == null || phone.trim().isEmpty()) {
            return;
        }

        applyCallerNumberToDraft(phone);
    }

    private void applyCallerNumberToDraft(String phone) {
        if (phone == null || phone.trim().isEmpty()) {
            return;
        }

        if (current == null || current.id != 0 || printing) {
            CallNumberStore.save(this, phone);
            return;
        }

        current.phone = Fmt.normalizeDigits(phone);

        if (activeCallerPhoneView != null) {
            activeCallerPhoneView.setText(nz(current.phone));
        }

        persist();
    }

    private void applyPendingCallerNumber() {
        String phone = CallNumberStore.consume(this);

        if (phone == null || phone.trim().isEmpty()) {
            return;
        }

        applyCallerNumberToDraft(phone);
        Ui.toast(this, "تم إدراج رقم الزبون تلقائياً");
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        if (intent != null
                && "iq.haji.cashier.NEW_CALL_NUMBER".equals(intent.getAction())) {

            String phone = intent.getStringExtra("caller_number");

            if (phone != null && !phone.trim().isEmpty()) {
                CallNumberStore.save(this, phone);
                applyPendingCallerNumber();
                refreshOrderUi();
            }
        }
    }

    @Override
    public void onStateChanged(PrinterManager.State state, String message) {
        refreshPrinterChip();
    }

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
        int widthDp = getResources().getConfiguration().screenWidthDp;
        int span = Math.max(2, (int) ((widthDp * 0.5f - 125) / 145));

        RecyclerView rvProducts = findViewById(R.id.rvProducts);
        rvProducts.setLayoutManager(new GridLayoutManager(this, span));

        productAdapter = new ProductAdapter(this::addProduct);
        rvProducts.setAdapter(productAdapter);

        RecyclerView rvCategories = findViewById(R.id.rvCategories);
        rvCategories.setLayoutManager(
                new LinearLayoutManager(
                        this,
                        LinearLayoutManager.VERTICAL,
                        false
                )
        );

        categoryAdapter = new CategoryAdapter(id -> {
            selectedCategory = id;
            categoryAdapter.setSelected(id);
            applyFilter();
        });

        rvCategories.setAdapter(categoryAdapter);

        rvLines.setLayoutManager(new LinearLayoutManager(this));

        lineAdapter = new OrderLineAdapter(new OrderLineAdapter.Listener() {
            @Override
            public void onPlus(OrderLine line) {
                changeQty(line, +1);
            }

            @Override
            public void onMinus(OrderLine line) {
                changeQty(line, -1);
            }

            @Override
            public void onDelete(OrderLine line) {
                removeLine(line);
            }

            @Override
            public void onNote(OrderLine line) {
                editNote(line);
            }
        });

        rvLines.setAdapter(lineAdapter);
    }

    private void setupActions() {
        findViewById(R.id.btnAdministration).setOnClickListener(v -> {
            String[] options = {
                    "🧾 الطلبات",
                    "🍕 الأصناف والأقسام",
                    "📊 التقارير",
                    "⏱ الورديات",
                    "🖨 إعدادات الطابعة",
                    "⚙ الإعدادات",
                    "📞 تفعيل رقم المتصل"
            };

            new AlertDialog.Builder(this)
                    .setTitle("إدارة مطعم الحجي")
                    .setItems(options, (dialog, which) -> {
                        switch (which) {
                            case 0:
                                open(OrdersActivity.class);
                                break;

                            case 1:
                                open(ProductsActivity.class);
                                break;

                            case 2:
                                open(ReportsActivity.class);
                                break;

                            case 3:
                                open(ShiftActivity.class);
                                break;

                            case 4:
                                open(PrinterSettingsActivity.class);
                                break;

                            case 5:
                                open(SettingsActivity.class);
                                break;

                            case 6:
                                requestCallerScreeningRole();
                                break;
                        }
                    })
                    .setNegativeButton("إغلاق", null)
                    .show();
        });

        chipShift.setOnClickListener(v -> open(ShiftActivity.class));
        chipPrinter.setOnClickListener(v -> open(PrinterSettingsActivity.class));

        for (int i = 0; i < typeViews.length; i++) {
            final String code = TYPE_CODES[i];
            typeViews[i].setOnClickListener(v -> setType(code));
        }

        EditText etSearch = findViewById(R.id.etSearch);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    CharSequence s,
                    int start,
                    int count,
                    int after
            ) {
            }

            @Override
            public void onTextChanged(
                    CharSequence s,
                    int start,
                    int before,
                    int count
            ) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                applyFilter();
            }
        });

        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE) {

                InputMethodManager imm =
                        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);

                if (imm != null) {
                    imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                }

                v.clearFocus();
                return true;
            }

            return false;
        });

        findViewById(R.id.btnDetails)
                .setOnClickListener(v -> showDetailsDialog());

        btnCancelOrder.setOnClickListener(v -> onCancelOrder());
        btnNewOrder.setOnClickListener(v -> onNewOrder());
        btnPrint.setOnClickListener(v -> onPrint());
    }

    private void open(Class<?> cls) {
        startActivity(new Intent(this, cls));
    }

    private void requestCallerScreeningRole() {
        RoleManager roleManager = getSystemService(RoleManager.class);

        if (roleManager == null
                || !roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            Ui.toast(this, "هذا الجهاز لا يدعم تفعيل خدمة فحص المكالمات");
            return;
        }

        if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            Ui.toast(this, "خدمة التقاط رقم المتصل مفعّلة بالفعل");
            return;
        }

        try {
            Intent intent = roleManager.createRequestRoleIntent(
                    RoleManager.ROLE_CALL_SCREENING);
            startActivityForResult(intent, REQUEST_CALL_SCREENING_ROLE);
        } catch (RuntimeException e) {
            Ui.toast(this, "تعذر فتح شاشة تفعيل خدمة رقم المتصل");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CALL_SCREENING_ROLE) {
            RoleManager roleManager = getSystemService(RoleManager.class);

            if (roleManager != null
                    && roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
                Ui.toast(this, "تم تفعيل خدمة التقاط رقم المتصل");
            } else {
                Ui.toast(this, "لم يتم تفعيل خدمة التقاط رقم المتصل");
            }
        }
    }
        private boolean locked() {
        return current != null && current.id != 0;
    }

    private void loadInitialOrder() {
        List<Order> pending = orders.listPendingPrint();

        if (!pending.isEmpty()) {
            Order order = orders.getOrder(pending.get(0).id);

            if (order != null) {
                current = order;

                if (pending.size() > 1) {
                    Ui.toast(this, "يوجد " + pending.size()
                            + " طلبات بانتظار الطباعة. الأحدث معروض هنا والبقية في شاشة الطلبات.");
                }

                return;
            }
        }

        Order draft = orders.loadDraft();

        if (draft != null) {
            current = draft;

            if (current.createdAt == 0) {
                current.createdAt = System.currentTimeMillis();
            }

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
            current.deliveryFee =
                    settings.getLong(AppSettings.DEFAULT_DELIVERY_FEE, 0);
        }
    }

    private void resetOrder() {
        orders.clearDraft();
        clearStoredCallerNumber();
        newOrder();
        refreshOrderUi();
    }

    private void clearStoredCallerNumber() {
        getSharedPreferences("haji_call_data", MODE_PRIVATE)
                .edit()
                .remove("latest_number")
                .remove("latest_call_time")
                .remove("pending_phone")
                .apply();

        lastProcessedCallTime = 0L;

        if (activeCallerPhoneView != null) {
            activeCallerPhoneView.setText("");
        }
    }

    private void syncCurrentWithDb() {
        if (printing || current == null || current.id == 0) {
            return;
        }

        Order fresh = orders.getOrder(current.id);

        if (fresh == null || !OrderStatus.PENDING_PRINT.equals(fresh.status)) {
            newOrder();
        } else {
            current = fresh;
        }
    }

    private boolean typeEnabled(String type) {
        if (OrderType.DELIVERY.equals(type)) {
            return settings.getBool(AppSettings.ENABLE_DELIVERY, true);
        }

        if (OrderType.DINE_IN.equals(type)) {
            return settings.getBool(AppSettings.ENABLE_DINE_IN, true);
        }

        return settings.getBool(AppSettings.ENABLE_TAKEAWAY, true);
    }

    private void normalizeType() {
        if (typeEnabled(current.type)) {
            return;
        }

        for (String type : TYPE_CODES) {
            if (typeEnabled(type)) {
                current.type = type;
                return;
            }
        }
    }

    private void persist() {
        current.recalc();

        if (!locked()) {
            orders.saveDraft(current);
        }

        refreshOrderUi();
    }

    private void toastLocked() {
        Ui.toast(this, "الطلب رقم " + current.id
                + " محفوظ بانتظار الطباعة. أعد المحاولة أو ألغِه.");
    }

    private void setType(String type) {
        if (printing) {
            return;
        }

        if (locked()) {
            toastLocked();
            return;
        }

        current.type = type;

        if (OrderType.DELIVERY.equals(type) && current.deliveryFee == 0) {
            current.deliveryFee =
                    settings.getLong(AppSettings.DEFAULT_DELIVERY_FEE, 0);
        }

        persist();
    }

    private void reloadCatalog() {
        List<Category> categories = catalog.getCategories(true);
        boolean found = selectedCategory == 0;

        for (Category category : categories) {
            if (category.id == selectedCategory) {
                found = true;
            }
        }

        if (!found) {
            selectedCategory = 0;
        }

        categoryAdapter.setCategories(categories, selectedCategory);
        allProducts = catalog.getProducts(0, null, true);
        applyFilter();
    }

    private void applyFilter() {
        String normalizedQuery = ArabicText.normalize(query);
        List<Product> filtered = new ArrayList<>();

        for (Product product : allProducts) {
            if (selectedCategory > 0 && product.categoryId != selectedCategory) {
                continue;
            }

            if (!normalizedQuery.isEmpty()
                    && !ArabicText.normalize(product.name).contains(normalizedQuery)) {
                continue;
            }

            filtered.add(product);
        }

        productAdapter.setItems(filtered);

        if (filtered.isEmpty()) {
            tvNoProducts.setText(allProducts.isEmpty()
                    ? "لا توجد أصناف. أضف أصنافاً من شاشة «الأصناف»."
                    : "لا توجد أصناف مطابقة");
            tvNoProducts.setVisibility(View.VISIBLE);
        } else {
            tvNoProducts.setVisibility(View.GONE);
        }
    }

    private void addProduct(Product product) {
        if (printing) {
            return;
        }

        if (locked()) {
            toastLocked();
            return;
        }

        for (OrderLine line : current.lines) {
            if (line.productId == product.id
                    && (line.note == null || line.note.trim().isEmpty())) {
                if (line.qty < 999) {
                    line.qty++;
                }

                persist();
                return;
            }
        }

        OrderLine line = new OrderLine();
        line.productId = product.id;
        line.name = product.name;
        line.unitPrice = product.price;
        line.qty = 1;
        current.lines.add(line);

        persist();
        rvLines.scrollToPosition(current.lines.size() - 1);
    }

    private void changeQty(OrderLine line, int delta) {
        if (printing) {
            return;
        }

        if (locked()) {
            toastLocked();
            return;
        }

        line.qty = Math.max(1, Math.min(999, line.qty + delta));
        persist();
    }

    private void removeLine(OrderLine line) {
        if (printing) {
            return;
        }

        if (locked()) {
            toastLocked();
            return;
        }

        current.lines.remove(line);
        persist();
    }

    private void editNote(final OrderLine line) {
        if (printing) {
            return;
        }

        if (locked()) {
            toastLocked();
            return;
        }

        final EditText edit = Ui.editText(
                this,
                "مثال: بدون بصل، حجم كبير، زيادة جبن",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                line.note == null ? "" : line.note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ملاحظة على: " + line.name)
                .setView(Ui.wrap(this, edit))
                .setPositiveButton(R.string.save, (d, w) -> {
                    String note = edit.getText().toString().trim();

                    if (note.length() > 80) {
                        note = note.substring(0, 80);
                    }

                    line.note = note;
                    persist();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        Ui.showRtl(dialog);
    }

    private void showDetailsDialog() {
        if (printing) {
            return;
        }

        if (locked()) {
            Ui.toast(this, "الطلب محفوظ ولا يمكن تعديل بياناته");
            return;
        }

        final boolean delivery = OrderType.DELIVERY.equals(current.type);
        final boolean allowDiscount =
                settings.getBool(AppSettings.ALLOW_DISCOUNT, true);

        View view = getLayoutInflater().inflate(
                R.layout.dialog_order_details, null);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        final EditText etName = view.findViewById(R.id.etCustomerName);
        final EditText etPhone = view.findViewById(R.id.etPhone);

        etPhone.setKeyListener(null);
        etPhone.setFocusable(false);
        etPhone.setFocusableInTouchMode(false);
        etPhone.setClickable(false);
        etPhone.setLongClickable(false);
        etPhone.setCursorVisible(false);
        etPhone.setTextIsSelectable(false);
        etPhone.setInputType(InputType.TYPE_NULL);

        activeCallerPhoneView = etPhone;

        final EditText etAddress = view.findViewById(R.id.etAddress);
        final EditText etDriver = view.findViewById(R.id.etDriver);
        final EditText etFee = view.findViewById(R.id.etDeliveryFee);
        final EditText etDiscount = view.findViewById(R.id.etDiscount);
        final EditText etNotes = view.findViewById(R.id.etNotes);

        view.findViewById(R.id.groupDelivery)
                .setVisibility(delivery ? View.VISIBLE : View.GONE);

        view.findViewById(R.id.groupDiscount)
                .setVisibility(allowDiscount ? View.VISIBLE : View.GONE);

        etName.setText(nz(current.customerName));
        etPhone.setText(nz(current.phone));
        etAddress.setText(nz(current.address));
        etDriver.setText(nz(current.driver));

        // اجعل حقل السائق يفتح قائمة الاختيار بدلاً من الكتابة اليدوية.
        etDriver.setKeyListener(null);
        etDriver.setFocusable(false);
        etDriver.setFocusableInTouchMode(false);
        etDriver.setClickable(true);
        etDriver.setOnClickListener(v -> showDriverPicker(etDriver));

        if (current.deliveryFee > 0) {
            etFee.setText(String.valueOf(current.deliveryFee));
        }

        if (current.discount > 0) {
            etDiscount.setText(String.valueOf(current.discount));
        }

        etNotes.setText(nz(current.notes));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("بيانات الطلب")
                .setView(view)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(x ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(button -> {
                            long fee = 0;
                            long discount = 0;

                            if (delivery) {
                                String value = etFee.getText().toString().trim();

                                if (!value.isEmpty()) {
                                    fee = Fmt.parseMoney(value);

                                    if (fee < 0) {
                                        etFee.setError("مبلغ غير صحيح");
                                        return;
                                    }
                                }
                            }

                            if (allowDiscount) {
                                String value =
                                        etDiscount.getText().toString().trim();

                                if (!value.isEmpty()) {
                                    discount = Fmt.parseMoney(value);

                                    if (discount < 0) {
                                        etDiscount.setError("مبلغ غير صحيح");
                                        return;
                                    }

                                    if (discount > current.subtotal) {
                                        etDiscount.setError(
                                                "الخصم أكبر من مجموع الطلب");
                                        return;
                                    }
                                }
                            }

                            current.customerName =
                                    etName.getText().toString().trim();

                            current.phone = Fmt.normalizeDigits(nz(current.phone));

                            current.notes =
                                    etNotes.getText().toString().trim();

                            if (delivery) {
                                current.address =
                                        etAddress.getText().toString().trim();

                                current.driver =
                                        etDriver.getText().toString().trim();

                                current.deliveryFee = fee;
                            }

                            if (allowDiscount) {
                                current.discount = discount;
                            }

                            dialog.dismiss();
                            persist();
                        }));

        dialog.setOnDismissListener(d -> {
            if (activeCallerPhoneView == etPhone) {
                activeCallerPhoneView = null;
            }
        });

        Ui.showRtl(dialog);
    }

    private void showDriverPicker(EditText target) {
        String saved = getSharedPreferences(
                "haji_driver_data",
                MODE_PRIVATE
        ).getString("drivers", "حسين\nأحمد\nعلي\nجبار");

        List<String> names = new ArrayList<>();

        if (saved != null) {
            for (String line : saved.split("\\r?\\n")) {
                String name = line.trim();

                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }

        names.add("بدون سائق");

        String[] options = names.toArray(new String[0]);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("اختيار السائق")
                .setItems(options, (d, which) -> {
                    String selected = options[which];

                    target.setText(
                            "بدون سائق".equals(selected) ? "" : selected
                    );
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        Ui.showRtl(dialog);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
        private boolean locked() {
        return current != null && current.id != 0;
    }

    private void loadInitialOrder() {
        List<Order> pending = orders.listPendingPrint();

        if (!pending.isEmpty()) {
            Order order = orders.getOrder(pending.get(0).id);

            if (order != null) {
                current = order;

                if (pending.size() > 1) {
                    Ui.toast(this, "يوجد " + pending.size()
                            + " طلبات بانتظار الطباعة. الأحدث معروض هنا والبقية في شاشة الطلبات.");
                }

                return;
            }
        }

        Order draft = orders.loadDraft();

        if (draft != null) {
            current = draft;

            if (current.createdAt == 0) {
                current.createdAt = System.currentTimeMillis();
            }

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
            current.deliveryFee =
                    settings.getLong(AppSettings.DEFAULT_DELIVERY_FEE, 0);
        }
    }

    private void resetOrder() {
        orders.clearDraft();
        clearStoredCallerNumber();
        newOrder();
        refreshOrderUi();
    }

    private void clearStoredCallerNumber() {
        getSharedPreferences("haji_call_data", MODE_PRIVATE)
                .edit()
                .remove("latest_number")
                .remove("latest_call_time")
                .remove("pending_phone")
                .apply();

        lastProcessedCallTime = 0L;

        if (activeCallerPhoneView != null) {
            activeCallerPhoneView.setText("");
        }
    }

    private void syncCurrentWithDb() {
        if (printing || current == null || current.id == 0) {
            return;
        }

        Order fresh = orders.getOrder(current.id);

        if (fresh == null || !OrderStatus.PENDING_PRINT.equals(fresh.status)) {
            newOrder();
        } else {
            current = fresh;
        }
    }

    private boolean typeEnabled(String type) {
        if (OrderType.DELIVERY.equals(type)) {
            return settings.getBool(AppSettings.ENABLE_DELIVERY, true);
        }

        if (OrderType.DINE_IN.equals(type)) {
            return settings.getBool(AppSettings.ENABLE_DINE_IN, true);
        }

        return settings.getBool(AppSettings.ENABLE_TAKEAWAY, true);
    }

    private void normalizeType() {
        if (typeEnabled(current.type)) {
            return;
        }

        for (String type : TYPE_CODES) {
            if (typeEnabled(type)) {
                current.type = type;
                return;
            }
        }
    }

    private void persist() {
        current.recalc();

        if (!locked()) {
            orders.saveDraft(current);
        }

        refreshOrderUi();
    }

    private void toastLocked() {
        Ui.toast(this, "الطلب رقم " + current.id
                + " محفوظ بانتظار الطباعة. أعد المحاولة أو ألغِه.");
    }

    private void setType(String type) {
        if (printing) {
            return;
        }

        if (locked()) {
            toastLocked();
            return;
        }

        current.type = type;

        if (OrderType.DELIVERY.equals(type) && current.deliveryFee == 0) {
            current.deliveryFee =
                    settings.getLong(AppSettings.DEFAULT_DELIVERY_FEE, 0);
        }

        persist();
    }

    private void reloadCatalog() {
        List<Category> categories = catalog.getCategories(true);
        boolean found = selectedCategory == 0;

        for (Category category : categories) {
            if (category.id == selectedCategory) {
                found = true;
            }
        }

        if (!found) {
            selectedCategory = 0;
        }

        categoryAdapter.setCategories(categories, selectedCategory);
        allProducts = catalog.getProducts(0, null, true);
        applyFilter();
    }

    private void applyFilter() {
        String normalizedQuery = ArabicText.normalize(query);
        List<Product> filtered = new ArrayList<>();

        for (Product product : allProducts) {
            if (selectedCategory > 0 && product.categoryId != selectedCategory) {
                continue;
            }

            if (!normalizedQuery.isEmpty()
                    && !ArabicText.normalize(product.name).contains(normalizedQuery)) {
                continue;
            }

            filtered.add(product);
        }

        productAdapter.setItems(filtered);

        if (filtered.isEmpty()) {
            tvNoProducts.setText(allProducts.isEmpty()
                    ? "لا توجد أصناف. أضف أصنافاً من شاشة «الأصناف»."
                    : "لا توجد أصناف مطابقة");
            tvNoProducts.setVisibility(View.VISIBLE);
        } else {
            tvNoProducts.setVisibility(View.GONE);
        }
    }

    private void addProduct(Product product) {
        if (printing) {
            return;
        }

        if (locked()) {
            toastLocked();
            return;
        }

        for (OrderLine line : current.lines) {
            if (line.productId == product.id
                    && (line.note == null || line.note.trim().isEmpty())) {
                if (line.qty < 999) {
                    line.qty++;
                }

                persist();
                return;
            }
        }

        OrderLine line = new OrderLine();
        line.productId = product.id;
        line.name = product.name;
        line.unitPrice = product.price;
        line.qty = 1;
        current.lines.add(line);

        persist();
        rvLines.scrollToPosition(current.lines.size() - 1);
    }

    private void changeQty(OrderLine line, int delta) {
        if (printing) {
            return;
        }

        if (locked()) {
            toastLocked();
            return;
        }

        line.qty = Math.max(1, Math.min(999, line.qty + delta));
        persist();
    }

    private void removeLine(OrderLine line) {
        if (printing) {
            return;
        }

        if (locked()) {
            toastLocked();
            return;
        }

        current.lines.remove(line);
        persist();
    }

    private void editNote(final OrderLine line) {
        if (printing) {
            return;
        }

        if (locked()) {
            toastLocked();
            return;
        }

        final EditText edit = Ui.editText(
                this,
                "مثال: بدون بصل، حجم كبير، زيادة جبن",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                line.note == null ? "" : line.note);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("ملاحظة على: " + line.name)
                .setView(Ui.wrap(this, edit))
                .setPositiveButton(R.string.save, (d, w) -> {
                    String note = edit.getText().toString().trim();

                    if (note.length() > 80) {
                        note = note.substring(0, 80);
                    }

                    line.note = note;
                    persist();
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        Ui.showRtl(dialog);
    }

    private void showDetailsDialog() {
        if (printing) {
            return;
        }

        if (locked()) {
            Ui.toast(this, "الطلب محفوظ ولا يمكن تعديل بياناته");
            return;
        }

        final boolean delivery = OrderType.DELIVERY.equals(current.type);
        final boolean allowDiscount =
                settings.getBool(AppSettings.ALLOW_DISCOUNT, true);

        View view = getLayoutInflater().inflate(
                R.layout.dialog_order_details, null);
        view.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        final EditText etName = view.findViewById(R.id.etCustomerName);
        final EditText etPhone = view.findViewById(R.id.etPhone);

        etPhone.setKeyListener(null);
        etPhone.setFocusable(false);
        etPhone.setFocusableInTouchMode(false);
        etPhone.setClickable(false);
        etPhone.setLongClickable(false);
        etPhone.setCursorVisible(false);
        etPhone.setTextIsSelectable(false);
        etPhone.setInputType(InputType.TYPE_NULL);

        activeCallerPhoneView = etPhone;

        final EditText etAddress = view.findViewById(R.id.etAddress);
        final EditText etDriver = view.findViewById(R.id.etDriver);
        final EditText etFee = view.findViewById(R.id.etDeliveryFee);
        final EditText etDiscount = view.findViewById(R.id.etDiscount);
        final EditText etNotes = view.findViewById(R.id.etNotes);

        view.findViewById(R.id.groupDelivery)
                .setVisibility(delivery ? View.VISIBLE : View.GONE);

        view.findViewById(R.id.groupDiscount)
                .setVisibility(allowDiscount ? View.VISIBLE : View.GONE);

        etName.setText(nz(current.customerName));
        etPhone.setText(nz(current.phone));
        etAddress.setText(nz(current.address));
        etDriver.setText(nz(current.driver));

        // اجعل حقل السائق يفتح قائمة الاختيار بدلاً من الكتابة اليدوية.
        etDriver.setKeyListener(null);
        etDriver.setFocusable(false);
        etDriver.setFocusableInTouchMode(false);
        etDriver.setClickable(true);
        etDriver.setOnClickListener(v -> showDriverPicker(etDriver));

        if (current.deliveryFee > 0) {
            etFee.setText(String.valueOf(current.deliveryFee));
        }

        if (current.discount > 0) {
            etDiscount.setText(String.valueOf(current.discount));
        }

        etNotes.setText(nz(current.notes));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("بيانات الطلب")
                .setView(view)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(x ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setOnClickListener(button -> {
                            long fee = 0;
                            long discount = 0;

                            if (delivery) {
                                String value = etFee.getText().toString().trim();

                                if (!value.isEmpty()) {
                                    fee = Fmt.parseMoney(value);

                                    if (fee < 0) {
                                        etFee.setError("مبلغ غير صحيح");
                                        return;
                                    }
                                }
                            }

                            if (allowDiscount) {
                                String value =
                                        etDiscount.getText().toString().trim();

                                if (!value.isEmpty()) {
                                    discount = Fmt.parseMoney(value);

                                    if (discount < 0) {
                                        etDiscount.setError("مبلغ غير صحيح");
                                        return;
                                    }

                                    if (discount > current.subtotal) {
                                        etDiscount.setError(
                                                "الخصم أكبر من مجموع الطلب");
                                        return;
                                    }
                                }
                            }

                            current.customerName =
                                    etName.getText().toString().trim();

                            current.phone = Fmt.normalizeDigits(nz(current.phone));

                            current.notes =
                                    etNotes.getText().toString().trim();

                            if (delivery) {
                                current.address =
                                        etAddress.getText().toString().trim();

                                current.driver =
                                        etDriver.getText().toString().trim();

                                current.deliveryFee = fee;
                            }

                            if (allowDiscount) {
                                current.discount = discount;
                            }

                            dialog.dismiss();
                            persist();
                        }));

        dialog.setOnDismissListener(d -> {
            if (activeCallerPhoneView == etPhone) {
                activeCallerPhoneView = null;
            }
        });

        Ui.showRtl(dialog);
    }

    private void showDriverPicker(EditText target) {
        String saved = getSharedPreferences(
                "haji_driver_data",
                MODE_PRIVATE
        ).getString("drivers", "حسين\nأحمد\nعلي\nجبار");

        List<String> names = new ArrayList<>();

        if (saved != null) {
            for (String line : saved.split("\\r?\\n")) {
                String name = line.trim();

                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }

        names.add("بدون سائق");

        String[] options = names.toArray(new String[0]);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("اختيار السائق")
                .setItems(options, (d, which) -> {
                    String selected = options[which];

                    target.setText(
                            "بدون سائق".equals(selected) ? "" : selected
                    );
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        Ui.showRtl(dialog);
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

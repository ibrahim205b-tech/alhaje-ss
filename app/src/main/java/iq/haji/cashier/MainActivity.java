package iq.haji.cashier;

import android.app.role.RoleManager;
import android.content.Intent;
import android.graphics.Rect;
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
import androidx.core.widget.NestedScrollView;
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
    private EditText etMainPhone, etMainAddress, etMainDriver;
    private boolean syncingMainFields = false;

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
    private NestedScrollView scrollOrderPanel;

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

        // لا نعلّم المكالمة كمعالجة أثناء وجود طلب محفوظ أو طباعة جارية،
        // ويبقى الرقم معلقاً حتى يصبح الطلب الجديد جاهزاً.
        if (current == null || current.id != 0 || printing) {
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

        String normalizedPhone = Fmt.normalizeDigits(phone.trim());

        if (normalizedPhone.isEmpty()) {
            return;
        }

        // لا نغيّر طلباً محفوظاً أو قيد الطباعة.
        // نحتفظ بالرقم حتى يصبح الطلب الجديد جاهزاً.
        if (current == null || current.id != 0 || printing) {
            CallNumberStore.save(this, normalizedPhone);
            return;
        }

        current.phone = normalizedPhone;

        // طُبّق الرقم على الطلب الحالي، فلا يبقى معلقاً للطلب التالي.
        getSharedPreferences("haji_call_data", MODE_PRIVATE)
                .edit()
                .remove("pending_phone")
                .apply();

        if (activeCallerPhoneView != null) {
            activeCallerPhoneView.setText(nz(current.phone));
        }

        persist();
    }

    private void applyPendingCallerNumber() {
        android.content.SharedPreferences prefs = getSharedPreferences(
                "haji_call_data", MODE_PRIVATE);

        String phone = prefs.getString("pending_phone", "");

        if (phone == null || phone.trim().isEmpty()) {
            return;
        }

        // لا نستهلك الرقم إذا كان الطلب محفوظاً أو الطباعة جارية.
        if (current == null || current.id != 0 || printing) {
            return;
        }

        prefs.edit().remove("pending_phone").apply();

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
        scrollOrderPanel = findViewById(R.id.scrollOrderPanel);
        etMainPhone = findViewById(R.id.etMainPhone);
        etMainAddress = findViewById(R.id.etMainAddress);
        etMainDriver = findViewById(R.id.etMainDriver);

        typeViews[0] = findViewById(R.id.tvTypeTakeaway);
        typeViews[1] = findViewById(R.id.tvTypeDelivery);
        typeViews[2] = findViewById(R.id.tvTypeDineIn);
    }

    private void setupLists() {
        // عرض منطقة الأصناف تقريباً: 14 من 19 من عرض الشاشة ناقص عمود الأقسام.
        // البطاقة بحجم متوسط، وأقل عدد ثلاثة أعمدة.
        int widthDp = getResources().getConfiguration().screenWidthDp;
        float productsDp = (widthDp - 41) * 14f / 19f - 116;
        int span = Math.max(3, (int) (productsDp / 125));

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

        setupMainCustomerFields();
    }

    private void setupMainCustomerFields() {
        // رقم الزبون في واجهة الكاشير للعرض فقط ويُملأ تلقائياً من رقم المتصل.
        // الترتيب مهم: setInputType و setKeyListener و setTextIsSelectable
        // تعيد تفعيل قابلية التركيز، لذلك نعطّلها بعدها.
        etMainPhone.setInputType(InputType.TYPE_NULL);
        etMainPhone.setKeyListener(null);
        etMainPhone.setTextIsSelectable(false);
        etMainPhone.setFocusable(false);
        etMainPhone.setFocusableInTouchMode(false);
        etMainPhone.setClickable(false);
        etMainPhone.setLongClickable(false);
        etMainPhone.setCursorVisible(false);

        etMainAddress.addTextChangedListener(new TextWatcher() {
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
                if (!canEditMainFields()) {
                    return;
                }

                current.address = s.toString().trim();
                saveDraftQuietly();
            }
        });

        etMainDriver.addTextChangedListener(new TextWatcher() {
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
                if (!canEditMainFields()) {
                    return;
                }

                current.driver = s.toString().trim();
                saveDraftQuietly();
            }
        });
    }

    private boolean canEditMainFields() {
        return !syncingMainFields
                && current != null
                && !locked()
                && !printing;
    }

    private void saveDraftQuietly() {
        current.recalc();
        orders.saveDraft(current);
    }

    private void syncMainField(EditText field, String value) {
        if (field == null) {
            return;
        }

        String text = field.getText() == null
                ? "" : field.getText().toString();

        if (!text.trim().equals(nz(value).trim())) {
            field.setText(nz(value));
        }
    }

    private void refreshMainCustomerFields(boolean isLocked) {
        syncingMainFields = true;

        try {
            syncMainField(etMainPhone, current.phone);
            syncMainField(etMainAddress, current.address);
            syncMainField(etMainDriver, current.driver);
        } finally {
            syncingMainFields = false;
        }

        boolean editable = !isLocked && !printing;

        etMainAddress.setEnabled(editable);
        etMainDriver.setEnabled(editable);
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

        for (int i = 0; i < current.lines.size(); i++) {
            OrderLine line = current.lines.get(i);

            if (line.productId == product.id
                    && (line.note == null || line.note.trim().isEmpty())) {
                if (line.qty < 999) {
                    line.qty++;
                }

                persist();
                scrollToLine(i);
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
        scrollToLine(current.lines.size() - 1);
    }

    /** يمرّر لوحة الطلب حتى يظهر الطبق المضاف مع أزرار + و −. */
    private void scrollToLine(final int position) {
        if (scrollOrderPanel == null || position < 0) {
            return;
        }

        // ننتظر قليلاً حتى تُرسم القائمة بعد التحديث.
        scrollOrderPanel.postDelayed(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }

            RecyclerView.LayoutManager manager = rvLines.getLayoutManager();
            View row = manager == null ? null : manager.findViewByPosition(position);

            if (row == null) {
                return;
            }

            Rect rect = new Rect();
            row.getDrawingRect(rect);
            scrollOrderPanel.offsetDescendantRectToMyCoords(row, rect);

            int top = scrollOrderPanel.getScrollY();
            int bottom = top + scrollOrderPanel.getHeight();

            if (rect.bottom > bottom) {
                scrollOrderPanel.smoothScrollTo(0, rect.bottom - scrollOrderPanel.getHeight());
            } else if (rect.top < top) {
                scrollOrderPanel.smoothScrollTo(0, rect.top);
            }
        }, 80L);
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

        // رقم الهاتف للعرض فقط. لا يسمح بإدخاله أو تغييره يدوياً.
        // الترتيب مهم: setInputType و setKeyListener و setTextIsSelectable
        // تعيد تفعيل قابلية التركيز، لذلك نعطّلها بعدها.
        etPhone.setInputType(InputType.TYPE_NULL);
        etPhone.setKeyListener(null);
        etPhone.setTextIsSelectable(false);
        etPhone.setFocusable(false);
        etPhone.setFocusableInTouchMode(false);
        etPhone.setClickable(false);
        etPhone.setLongClickable(false);
        etPhone.setCursorVisible(false);

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

        // اختيار السائق من القائمة.
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

    private void onNewOrder() {
        if (printing) {
            return;
        }

        if (locked()) {
            toastLocked();
            return;
        }

        if (current.lines.isEmpty()) {
            resetOrder();
            return;
        }

        confirm(
                "طلب جديد",
                "سيتم تجاهل الطلب الحالي غير المطبوع. متابعة؟",
                "تجاهل وابدأ طلباً جديداً",
                this::resetOrder);
    }

    private void onCancelOrder() {
        if (printing) {
            return;
        }

        if (!locked()) {
            if (current.lines.isEmpty()) {
                Ui.toast(this, "الطلب فارغ");
                return;
            }

            confirm(
                    "إلغاء الطلب",
                    "حذف جميع أصناف الطلب الحالي؟ لم يُحفظ بعد ولن يظهر في السجل.",
                    "حذف الطلب",
                    this::resetOrder);
            return;
        }

        final long id = current.id;

        final EditText reason = Ui.editText(
                this, "سبب الإلغاء (اختياري)", InputType.TYPE_CLASS_TEXT, "");

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("إلغاء الطلب رقم " + id)
                .setMessage("سيُسجَّل الطلب «ملغى» ويبقى في سجل الطلبات ولا يُحتسب في المبيعات.")
                .setView(Ui.wrap(this, reason))
                .setPositiveButton("إلغاء الطلب", (d, w) -> {
                    final String why = reason.getText().toString().trim();
                    PinDialog.require(this, () -> doCancelSaved(id, why));
                })
                .setNegativeButton(R.string.back, null)
                .create();

        Ui.showRtl(dialog);
    }

    private void doCancelSaved(long id, String reason) {
        try {
            orders.cancelOrder(id, reason);
        } catch (AppException e) {
            Ui.toast(this, e.getMessage());
            return;
        }

        clearStoredCallerNumber();
        newOrder();
        refreshOrderUi();
        Ui.toast(this, "أُلغي الطلب رقم " + id);
    }

    private void confirm(
            String title,
            String message,
            String positive,
            final Runnable onYes) {

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positive, (d, w) -> onYes.run())
                .setNegativeButton(R.string.back, null)
                .create();

        Ui.showRtl(dialog);
    }

    private void onPrint() {
        if (printing || ReceiptPrinter.isBusy()) {
            return;
        }

        if (!locked()) {
            if (current.lines.isEmpty()) {
                Ui.toast(this, "الطلب فارغ. أضف صنفاً أولاً.");
                return;
            }

            if (shifts.getOpenShift() == null) {
                showNoShiftDialog();
                return;
            }

            if (OrderType.DELIVERY.equals(current.type)
                    && isBlank(current.address)) {
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

    private void setBusy(boolean busy) {
        printing = busy;
        refreshOrderUi();
    }

    private void onPrintResult(ReceiptPrinter.Result result) {
        printing = false;

        if (isFinishing() || isDestroyed()) {
            return;
        }

        if (result.success) {
            ensurePrintedState(result.orderId);

            // احتفظ برقم المكالمة التي وصلت أثناء طباعة الطلب السابق.
            android.content.SharedPreferences callPrefs = getSharedPreferences(
                    "haji_call_data", MODE_PRIVATE);
            String queuedPhone = callPrefs.getString("pending_phone", "");

            clearStoredCallerNumber();
            newOrder();

            if (queuedPhone != null && !queuedPhone.trim().isEmpty()) {
                applyCallerNumberToDraft(queuedPhone);
            }

            refreshOrderUi();
            showSuccess(result.message);
            return;
        }

        if (result.orderSaved && result.orderId != 0) {
            Order fresh = orders.getOrder(result.orderId);

            if (fresh != null
                    && OrderStatus.PENDING_PRINT.equals(fresh.status)) {
                current = fresh;
            } else if (fresh != null) {
                newOrder();
            }
        }

        refreshOrderUi();
        showFailure(result);
    }

    private void ensurePrintedState(long orderId) {
        if (orderId == 0) {
            return;
        }

        Order order = orders.getOrder(orderId);

        if (order != null
                && OrderStatus.PENDING_PRINT.equals(order.status)) {
            try {
                orders.markPrinted(orderId);
            } catch (RuntimeException ignored) {
                // يبقى الطلب محفوظاً ويمكن التحقق من حالته لاحقاً.
            }
        }
    }

    private void showSuccess(String message) {
        Snackbar snackbar = Snackbar.make(
                findViewById(R.id.root), message, Snackbar.LENGTH_LONG);

        snackbar.setDuration(9000);
        snackbar.setAction(R.string.ok, v -> {});

        View text = snackbar.getView().findViewById(
                com.google.android.material.R.id.snackbar_text);

        if (text instanceof TextView) {
            ((TextView) text).setMaxLines(6);
        }

        snackbar.show();
    }

    private void showFailure(ReceiptPrinter.Result result) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("لم تتم الطباعة")
                .setMessage(result.message)
                .setNeutralButton(
                        "إعدادات الطابعة",
                        (d, w) -> open(PrinterSettingsActivity.class));

        if (result.orderSaved) {
            builder.setPositiveButton(
                    R.string.retry, (d, w) -> onPrint());
            builder.setNegativeButton(R.string.close, null);
        } else {
            builder.setPositiveButton(R.string.ok, null);
        }

        Ui.showRtl(builder.create());
    }

    private void showNoShiftDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("لا توجد وردية مفتوحة")
                .setMessage("لا يمكن حفظ الطلبات وطباعتها بدون وردية. ابدأ وردية الآن؟")
                .setPositiveButton(
                        "بدء وردية", (d, w) -> open(ShiftActivity.class))
                .setNegativeButton(R.string.cancel, null)
                .create();

        Ui.showRtl(dialog);
    }

    private void showNoPrinterDialog() {
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("لم تُحدد طابعة")
                .setMessage("اختر طابعة (بلوتوث أو شبكة) من إعدادات الطابعة قبل الطباعة. الطلب باقٍ كما هو ولم يُحفظ بعد.")
                .setPositiveButton(
                        "إعدادات الطابعة",
                        (d, w) -> open(PrinterSettingsActivity.class))
                .setNegativeButton(R.string.cancel, null)
                .create();

        Ui.showRtl(dialog);
    }

    private void maybeShowFirstRun() {
        if (firstRunShown
                || settings.getBool(AppSettings.SETUP_DONE, false)) {
            return;
        }

        firstRunShown = true;

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("مرحباً بك في دليفري الحجي")
                .setMessage("قبل أول استخدام:\n\n"
                        + "1) تهيئة الطابعة: سيطلب التطبيق إذن «الأجهزة القريبة» للبحث عن طابعة البلوتوث "
                        + "والاتصال بها فقط. لا يُستخدم هذا الإذن لتحديد موقعك. ويمكنك بدلاً من ذلك استخدام "
                        + "طابعة شبكية بعنوان IP.\n\n"
                        + "2) ابدأ وردية من شاشة «الوردية» قبل أول طلب.")
                .setCancelable(false)
                .setPositiveButton("تهيئة الطابعة الآن", (d, w) -> {
                    settings.setBool(AppSettings.SETUP_DONE, true);
                    Intent intent = new Intent(
                            this, PrinterSettingsActivity.class);
                    intent.putExtra("first_run", true);
                    startActivity(intent);
                })
                .setNegativeButton(
                        "لاحقاً",
                        (d, w) -> settings.setBool(
                                AppSettings.SETUP_DONE, true))
                .create();

        Ui.showRtl(dialog);
    }

    private void refreshOrderUi() {
        current.recalc();
        boolean isLocked = locked();
        boolean delivery = OrderType.DELIVERY.equals(current.type);

        tvOrderNo.setText("طلب رقم "
                + (isLocked ? current.id : orders.nextOrderNumber()));
        tvOrderTime.setText(Fmt.dateTime(current.createdAt));

        for (int i = 0; i < typeViews.length; i++) {
            boolean selected = TYPE_CODES[i].equals(current.type);

            typeViews[i].setVisibility(
                    typeEnabled(TYPE_CODES[i]) ? View.VISIBLE : View.GONE);

            typeViews[i].setSelected(selected);
            typeViews[i].setTextColor(ContextCompat.getColor(
                    this,
                    selected ? R.color.white : R.color.text_primary));
        }

        if (isLocked) {
            tvLockedBanner.setText("الطلب رقم " + current.id
                    + " محفوظ بانتظار الطباعة. اضغط «إعادة المحاولة» أو ألغِ الطلب.");
            tvLockedBanner.setVisibility(View.VISIBLE);
        } else {
            tvLockedBanner.setVisibility(View.GONE);
        }

        List<String> parts = new ArrayList<>();

        if (!isBlank(current.customerName)) {
            parts.add(current.customerName.trim());
        }

        if (!isBlank(current.phone)) {
            parts.add(current.phone.trim());
        }

        if (delivery && !isBlank(current.address)) {
            parts.add(current.address.trim());
        }

        if (delivery && !isBlank(current.driver)) {
            parts.add("السائق: " + current.driver.trim());
        }

        if (parts.isEmpty()) {
            tvDetailsSummary.setText(
                    delivery ? "أدخل عنوان التوصيل (مطلوب)" : "لا توجد بيانات زبون");

            tvDetailsSummary.setTextColor(ContextCompat.getColor(
                    this,
                    delivery ? R.color.error : R.color.text_hint));
        } else {
            tvDetailsSummary.setText(join(parts, "  •  "));
            tvDetailsSummary.setTextColor(ContextCompat.getColor(
                    this, R.color.text_secondary));
        }

        refreshMainCustomerFields(isLocked);

        lineAdapter.setLines(current.lines, isLocked);

        tvEmptyOrder.setVisibility(
                current.lines.isEmpty() ? View.VISIBLE : View.GONE);

        tvSubtotal.setText(Fmt.money(current.subtotal));

        rowDiscount.setVisibility(
                current.discount > 0 ? View.VISIBLE : View.GONE);

        tvDiscount.setText("- " + Fmt.money(current.discount));

        rowDelivery.setVisibility(
                delivery && current.deliveryFee > 0 ? View.VISIBLE : View.GONE);

        tvDelivery.setText(Fmt.money(current.deliveryFee));
        tvTotal.setText(Fmt.money(current.total));

        boolean canPrint = !printing
                && (isLocked || !current.lines.isEmpty());

        btnPrint.setEnabled(canPrint);
        btnPrint.setAlpha(canPrint ? 1f : 0.5f);

        btnPrint.setText(
                printing ? "جارٍ الطباعة..."
                        : (isLocked ? "إعادة المحاولة" : "طباعة الفاتورة"));

        btnCancelOrder.setAlpha(printing ? 0.5f : 1f);
        btnNewOrder.setAlpha(printing ? 0.5f : 1f);
    }

    private static String join(List<String> parts, String separator) {
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                result.append(separator);
            }

            result.append(parts.get(i));
        }

        return result.toString();
    }

    private void refreshChips() {
        Shift shift = shifts.getOpenShift();

        if (shift == null) {
            chipShift.setText("لا توجد وردية — ابدأ وردية");
            chipShift.setTextColor(ContextCompat.getColor(
                    this, R.color.error));
        } else {
            chipShift.setText("الوردية #" + shift.id + " مفتوحة");
            chipShift.setTextColor(ContextCompat.getColor(
                    this, R.color.success));
        }

        refreshPrinterChip();
    }

    private void refreshPrinterChip() {
        PrinterManager manager = PrinterManager.get(this);
        String text;
        int color;

        if (!manager.hasSelectedPrinter()) {
            text = "الطابعة: غير محددة";
            color = R.color.warning;
        } else {
            switch (manager.getState()) {
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
                    break;
            }
        }

        chipPrinter.setText(text);
        chipPrinter.setTextColor(ContextCompat.getColor(this, color));
    }
}

package iq.haji.cashier.print;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import iq.haji.cashier.R;
import iq.haji.cashier.data.AppSettings;
import iq.haji.cashier.model.Order;
import iq.haji.cashier.model.OrderLine;
import iq.haji.cashier.model.OrderType;
import iq.haji.cashier.util.Fmt;

public class ReceiptRenderer {

    public static final String KEY_WIDTH_DOTS = "print_width_dots";
    private static final int MAX_HEIGHT = 16000;

    private final Context ctx;
    private final AppSettings st;
    private final int paperMm;
    private final int width;
    private final int margin;
    private final float base;
    private final String align;

    public ReceiptRenderer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.st = new AppSettings(ctx);
        this.paperMm = st.paperWidthMm();

        int custom = st.getInt(KEY_WIDTH_DOTS, 0);
        int w = paperMm == 58 ? 384 : 576;

        if (custom >= 200 && custom <= 832) {
            w = custom;
        }

        this.width = (w / 8) * 8;
        this.margin = Math.max(
                0,
                Math.min(st.getInt(AppSettings.PRINT_MARGIN_DOTS, 8), 60)
        );

        float scale =
                st.getInt(AppSettings.PRINT_FONT_SCALE, 1) == 2
                        ? 1.3f : 1f;

        this.base = (paperMm == 58 ? 22f : 26f) * scale;
        this.align = st.get(AppSettings.PRINT_ALIGN, "RIGHT");
    }

    public int widthDots() {
        return width;
    }

    public Bitmap render(final Order o) throws PrintException {
        return renderWith(new Content() {
            @Override
            public void build(Sheet s) {
                buildReceipt(s, o);
            }
        });
    }

    public Bitmap renderTest(final String connectionDescription)
            throws PrintException {
        return renderWith(new Content() {
            @Override
            public void build(Sheet s) {
                buildTest(s, connectionDescription);
            }
        });
    }

    private interface Content {
        void build(Sheet s);
    }

    private Bitmap renderWith(Content c) throws PrintException {
        try {
            Sheet measure = new Sheet(null);
            c.build(measure);

            int h = (int) Math.ceil(measure.y) + 16;

            if (h > MAX_HEIGHT) {
                throw new PrintException("الفاتورة طويلة جداً للطباعة");
            }

            Bitmap bmp = Bitmap.createBitmap(
                    width,
                    h,
                    Bitmap.Config.ARGB_8888
            );

            Canvas cv = new Canvas(bmp);
            cv.drawColor(Color.WHITE);

            Sheet draw = new Sheet(cv);
            c.build(draw);

            return bmp;

        } catch (OutOfMemoryError e) {
            throw new PrintException(
                    "الذاكرة غير كافية لإنشاء صورة الفاتورة",
                    e
            );
        }
    }

    private void buildReceipt(Sheet s, Order o) {
        boolean delivery = OrderType.DELIVERY.equals(o.type);

        String name = st.restaurantName();
        String phone = st.get(
                AppSettings.RESTAURANT_PHONE, ""
        ).trim();

        String addr = st.get(
                AppSettings.RESTAURANT_ADDRESS, ""
        ).trim();

        // رأس الوصل
        s.space(2);
        s.center(name, base * 1.45f, true);

        if (!phone.isEmpty()) {
            s.center("هاتف: " + phone, base * 0.9f, false);
        }

        if (!addr.isEmpty()) {
            s.center(addr, base * 0.85f, false);
        }

        // رقم الفاتورة واضح في منتصف الوصل
        s.space(2);
        s.center("#" + o.id, base * 1.35f, true);

        // التاريخ مباشرة تحت رقم الفاتورة
        s.center(
                Fmt.dateTime(o.createdAt),
                base * 0.85f,
                false
        );

        s.rule();

        // معلومات الطلب
        if (!delivery) {
            s.pair(
                    "النوع:",
                    OrderType.label(o.type),
                    base * 0.9f,
                    false
            );

            if (notEmpty(o.cashierName)) {
                s.pair(
                        "الموظف:",
                        o.cashierName,
                        base * 0.9f,
                        false
                );
            }

            if (o.shiftId > 0) {
                s.pair(
                        "الوردية:",
                        "#" + o.shiftId,
                        base * 0.9f,
                        false
                );
            }
        }

        if (notEmpty(o.customerName)) {
            s.pair("الزبون:", o.customerName, base, false);
        }

        if (notEmpty(o.phone)) {
            s.pair("رقم الزبون:", o.phone, base, false);
        }

        // يُطبع العنوان والسائق متى ما أُدخلا، لأن واجهة الكاشير
        // تحفظهما للطلب مباشرة بغض النظر عن نوعه.
        // العنوان بخط عريض وأكبر قليلاً حتى يُقرأ بوضوح.
        if (notEmpty(o.address)) {
            s.info(
                    "العنوان: " + o.address.trim(),
                    base * 1.15f,
                    true
            );
        }

        if (notEmpty(o.driver)) {
            s.pair("السائق:", o.driver.trim(), base, false);
        }

        s.rule();

        // جدول الأصناف
        s.itemRow("الصنف", "الكمية", "المبلغ", true, null);
        s.rule();

        for (OrderLine l : o.lines) {
            s.itemRow(
                    l.name,
                    String.valueOf(l.qty),
                    Fmt.number(l.total()),
                    false,
                    l.note
            );
        }

        s.rule();

        // الحسابات
        s.pair("المجموع:", Fmt.money(o.subtotal), base, false);

        if (o.discount > 0) {
            s.pair("الخصم:", Fmt.money(o.discount), base, false);
        }

        if (o.deliveryFee > 0) {
            s.pair(
                    "أجور التوصيل:",
                    Fmt.money(o.deliveryFee),
                    base,
                    false
            );
        }

        s.rule();

        s.center("الإجمالي", base, true);
        s.center(Fmt.money(o.total), base * 1.4f, true);

        if (notEmpty(o.paymentMethod)) {
            s.pair("الدفع:", o.paymentMethod, base * 0.9f, false);
        }

        if (notEmpty(o.notes)) {
            s.rule();
            s.info(
                    "ملاحظات: " + o.notes.trim(),
                    base * 0.9f,
                    false
            );
        }

        s.rule();

        String footer = st.get(
                AppSettings.RECEIPT_FOOTER,
                "شكراً لزيارتكم"
        ).trim();

        if (!footer.isEmpty()) {
            s.center(footer, base, true);
        }

        if (o.printCount > 0) {
            s.center(
                    "(نسخة معاد طباعتها)",
                    base * 0.8f,
                    false
            );
        }

        s.space(4);
    }

    private void buildTest(Sheet s, String connection) {
        s.center("اختبار الطباعة", base * 1.4f, true);
        s.center(st.restaurantName(), base * 1.1f, true);

        String phone = st.get(
                AppSettings.RESTAURANT_PHONE, ""
        ).trim();

        if (!phone.isEmpty()) {
            s.center("هاتف: " + phone, base * 0.9f, false);
        }

        s.rule();

        s.pair(
                "عرض الورق:",
                paperMm + " مم (" + width + " نقطة)",
                base * 0.9f,
                false
        );

        s.pair("الاتصال:", connection, base * 0.9f, false);

        s.pair(
                "الوقت:",
                Fmt.dateTime(System.currentTimeMillis()),
                base * 0.9f,
                false
        );

        s.pair(
                "الإجمالي:",
                Fmt.money(125000),
                base * 1.1f,
                true
        );

        s.rule();

        s.info(
                "أرقام: 0123456789 — مبلغ: 12,500 د.ع",
                base * 0.9f,
                false
        );

        s.edgeMarks();
        s.rule();
        s.center("نهاية الاختبار", base, true);
        s.space(4);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private final class Sheet {
        private final Canvas canvas;
        float y = 2;

        Sheet(Canvas canvas) {
            this.canvas = canvas;
        }

        int cw() {
            return width - 2 * margin;
        }

        TextPaint paint(float size, boolean bold) {
            TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.BLACK);
            p.setTextSize(size);
            p.setTypeface(
                    bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT
            );
            return p;
        }

        float text(
                String value,
                float x,
                int w,
                float size,
                boolean bold,
                Layout.Alignment alignment
        ) {
            if (value == null || value.isEmpty() || w <= 0) {
                return 0;
            }

            TextPaint p = paint(size, bold);

            StaticLayout layout = StaticLayout.Builder.obtain(
                            value,
                            0,
                            value.length(),
                            p,
                            w
                    )
                    .setAlignment(alignment)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1f)
                    .build();

            if (canvas != null) {
                canvas.save();
                canvas.translate(x, y);
                layout.draw(canvas);
                canvas.restore();
            }

            return layout.getHeight();
        }

        void center(String value, float size, boolean bold) {
            y += text(
                    value,
                    margin,
                    cw(),
                    size,
                    bold,
                    Layout.Alignment.ALIGN_CENTER
            ) + 2;
        }

        void info(String value, float size, boolean bold) {
            Layout.Alignment alignment;

            switch (align) {
                case "LEFT":
                    alignment = Layout.Alignment.ALIGN_OPPOSITE;
                    break;

                case "CENTER":
                    alignment = Layout.Alignment.ALIGN_CENTER;
                    break;

                default:
                    alignment = Layout.Alignment.ALIGN_NORMAL;
                    break;
            }

            y += text(
                    value,
                    margin,
                    cw(),
                    size,
                    bold,
                    alignment
            ) + 2;
        }

        void pair(
                String right,
                String left,
                float size,
                boolean bold
        ) {
            TextPaint p = paint(size, bold);

            int leftWidth = (int) Math.ceil(
                    p.measureText(left == null ? "" : left)
            ) + 4;

            leftWidth = Math.min(
                    leftWidth,
                    (int) (cw() * 0.62f)
            );

            int gap = 8;
            int rightWidth = cw() - leftWidth - gap;

            float rightHeight = text(
                    right,
                    margin + leftWidth + gap,
                    rightWidth,
                    size,
                    bold,
                    Layout.Alignment.ALIGN_NORMAL
            );

            float leftHeight = text(
                    left,
                    margin,
                    leftWidth,
                    size,
                    bold,
                    Layout.Alignment.ALIGN_OPPOSITE
            );

            y += Math.max(rightHeight, leftHeight) + 2;
        }

        void itemRow(
                String name,
                String qty,
                String total,
                boolean bold,
                String note
        ) {
            int contentWidth = cw();
            int totalWidth = (int) (contentWidth * 0.30f);
            int qtyWidth = (int) (contentWidth * 0.16f);
            int nameWidth = contentWidth - totalWidth - qtyWidth - 8;

            float nameX = margin + totalWidth + qtyWidth + 8;

            float nameHeight = text(
                    name,
                    nameX,
                    nameWidth,
                    base * 0.9f,
                    bold,
                    Layout.Alignment.ALIGN_NORMAL
            );

            float qtyHeight = text(
                    qty,
                    margin + totalWidth,
                    qtyWidth,
                    base * 0.9f,
                    bold,
                    Layout.Alignment.ALIGN_CENTER
            );

            float totalHeight = text(
                    total,
                    margin,
                    totalWidth,
                    base * 0.9f,
                    bold,
                    Layout.Alignment.ALIGN_OPPOSITE
            );

            y += Math.max(
                    nameHeight,
                    Math.max(qtyHeight, totalHeight)
            ) + 2;

            if (notEmpty(note)) {
                y += text(
                        "• " + note.trim(),
                        nameX,
                        nameWidth,
                        base * 0.75f,
                        false,
                        Layout.Alignment.ALIGN_NORMAL
                ) + 2;
            }
        }

        void rule() {
            if (canvas != null) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.BLACK);
                p.setStrokeWidth(1.5f);

                canvas.drawLine(
                        margin,
                        y + 2,
                        width - margin,
                        y + 2,
                        p
                );
            }

            y += 8;
        }

        void edgeMarks() {
            if (canvas != null) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.BLACK);
                p.setStrokeWidth(2f);

                canvas.drawLine(
                        margin, y, margin, y + 20, p
                );

                canvas.drawLine(
                        width - margin, y,
                        width - margin, y + 20, p
                );

                canvas.drawLine(
                        margin, y + 10,
                        width - margin, y + 10, p
                );
            }

            y += 24;
        }

        void space(float height) {
            y += height;
        }
    }
}

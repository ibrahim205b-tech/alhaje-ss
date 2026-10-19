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

/**
 * يرسم الإيصال العربي RTL كصورة بعرض الورق. محرك نصوص أندرويد يتولى ربط الحروف واتجاه الأرقام،
 * ثم تُرسل الصورة للطابعة، فلا تحتاج الطابعة لدعم العربية.
 * العرض بالنقاط: 58mm = 384، 80mm = 576 (يمكن تغييره بالمفتاح print_width_dots لطابعات 80mm ذات 512 نقطة).
 */
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
        if (custom >= 200 && custom <= 832) w = custom;
        this.width = (w / 8) * 8;
        this.margin = Math.max(0, Math.min(st.getInt(AppSettings.PRINT_MARGIN_DOTS, 8), 60));
        float scale = st.getInt(AppSettings.PRINT_FONT_SCALE, 1) == 2 ? 1.3f : 1f;
        this.base = (paperMm == 58 ? 22f : 26f) * scale;
        this.align = st.get(AppSettings.PRINT_ALIGN, "RIGHT");
    }

    public int widthDots() {
        return width;
    }

    // ---------------- نقاط الدخول ----------------

    public Bitmap render(final Order o) throws PrintException {
        return renderWith(new Content() {
            @Override
            public void build(Sheet s) {
                buildReceipt(s, o);
            }
        });
    }

    public Bitmap renderTest(final String connectionDescription) throws PrintException {
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
            int h = (int) Math.ceil(measure.y) + 24;
            if (h > MAX_HEIGHT) throw new PrintException("الفاتورة طويلة جداً للطباعة");
            Bitmap bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(bmp);
            cv.drawColor(Color.WHITE);
            Sheet draw = new Sheet(cv);
            c.build(draw);
            return bmp;
        } catch (OutOfMemoryError e) {
            throw new PrintException("الذاكرة غير كافية لإنشاء صورة الفاتورة", e);
        }
    }

    // ---------------- محتوى الفاتورة ----------------

    private void buildReceipt(Sheet s, Order o) {
        String name = st.restaurantName();
        s.center(name, base * 1.5f, true);
        s.center(ctx.getString(R.string.app_name), base * 0.9f, false);
        String addr = st.get(AppSettings.RESTAURANT_ADDRESS, "").trim();
        String phone = st.get(AppSettings.RESTAURANT_PHONE, "").trim();
        if (!addr.isEmpty()) s.center(addr, base * 0.9f, false);
        if (!phone.isEmpty()) s.center("هاتف: " + phone, base * 0.9f, false);
        s.rule();

        s.pair("رقم الفاتورة:", String.valueOf(o.id), base * 1.15f, true);
        s.pair("التاريخ:", Fmt.dateTime(o.createdAt), base, false);
        s.pair("النوع:", OrderType.label(o.type), base, false);
        if (notEmpty(o.cashierName)) s.pair("الموظف:", o.cashierName, base, false);
        if (o.shiftId > 0) s.pair("الوردية:", "#" + o.shiftId, base, false);
        if (notEmpty(o.customerName)) s.pair("الزبون:", o.customerName, base, false);
        if (notEmpty(o.phone)) s.pair("رقم الزبون:", o.phone, base, false);
        if (OrderType.DELIVERY.equals(o.type) && notEmpty(o.address)) {
            s.info("العنوان: " + o.address.trim(), base, false);
        }
        if (notEmpty(o.driver)) s.pair("السائق:", o.driver, base, false);
        s.rule();

        s.itemRow("الصنف", "الكمية", "المبلغ", true, null);
        s.rule();
        for (OrderLine l : o.lines) {
            s.itemRow(l.name, String.valueOf(l.qty), Fmt.number(l.total()), false, l.note);
        }
        s.rule();

        s.pair("المجموع:", Fmt.money(o.subtotal), base, false);
        if (o.discount > 0) s.pair("الخصم:", Fmt.money(o.discount), base, false);
        if (o.deliveryFee > 0) s.pair("أجور التوصيل:", Fmt.money(o.deliveryFee), base, false);
        s.rule();
        s.pair("الإجمالي:", Fmt.money(o.total), base * 1.4f, true);
        if (notEmpty(o.paymentMethod)) s.pair("الدفع:", o.paymentMethod, base, false);
        if (notEmpty(o.notes)) {
            s.rule();
            s.info("ملاحظات: " + o.notes.trim(), base, false);
        }
        s.rule();
        String footer = st.get(AppSettings.RECEIPT_FOOTER, "شكراً لزيارتكم").trim();
        if (!footer.isEmpty()) s.center(footer, base, true);
        if (o.printCount > 0) s.center("(نسخة معاد طباعتها)", base * 0.85f, false);
        s.space(10);
    }

    private void buildTest(Sheet s, String connection) {
        s.center("اختبار الطباعة", base * 1.5f, true);
        s.center(st.restaurantName(), base * 1.1f, true);
        s.center(ctx.getString(R.string.app_name), base * 0.9f, false);
        s.rule();
        s.pair("عرض الورق:", paperMm + " مم (" + width + " نقطة)", base, false);
        s.pair("الاتصال:", connection, base, false);
        s.pair("الوقت:", Fmt.dateTime(System.currentTimeMillis()), base, false);
        s.pair("الإجمالي:", Fmt.money(125000), base * 1.2f, true);
        s.rule();
        s.info("أرقام: 0123456789 — مبلغ: 12,500 د.ع", base, false);
        s.info("إذا ظهر هذا النص كاملاً ومقروءاً وبداية ونهاية السطور داخل علامات الحواف فالإعدادات سليمة.",
                base * 0.9f, false);
        s.edgeMarks();
        s.rule();
        s.center("نهاية الاختبار", base, true);
        s.space(10);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    // ---------------- أداة الرسم ----------------

    /** ورقة رسم: إذا كان canvas = null تقيس الارتفاع فقط (مرحلة القياس). */
    private final class Sheet {
        private final Canvas canvas;
        float y = 6;

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
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            return p;
        }

        /** يرسم نصاً في عمود ويعيد ارتفاعه. المحاذاة NORMAL = اليمين لأن الاتجاه RTL. */
        float text(String s, float x, int w, float size, boolean bold, Layout.Alignment al) {
            if (s == null || s.isEmpty() || w <= 0) return 0;
            StaticLayout l = StaticLayout.Builder.obtain(s, 0, s.length(), paint(size, bold), w)
                    .setAlignment(al)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1f)
                    .build();
            if (canvas != null) {
                canvas.save();
                canvas.translate(x, y);
                l.draw(canvas);
                canvas.restore();
            }
            return l.getHeight();
        }

        void center(String s, float size, boolean bold) {
            y += text(s, margin, cw(), size, bold, Layout.Alignment.ALIGN_CENTER) + 4;
        }

        void info(String s, float size, boolean bold) {
            Layout.Alignment al;
            switch (align) {
                case "LEFT":
                    al = Layout.Alignment.ALIGN_OPPOSITE;
                    break;
                case "CENTER":
                    al = Layout.Alignment.ALIGN_CENTER;
                    break;
                default:
                    al = Layout.Alignment.ALIGN_NORMAL;
            }
            y += text(s, margin, cw(), size, bold, al) + 4;
        }

        /** سطر بعنصرين: التسمية يميناً والقيمة يساراً. */
        void pair(String right, String left, float size, boolean bold) {
            TextPaint p = paint(size, bold);
            int lw = (int) Math.ceil(p.measureText(left)) + 4;
            lw = Math.min(lw, (int) (cw() * 0.62f));
            int gap = 10;
            int rw = cw() - lw - gap;
            float h1 = text(right, margin + lw + gap, rw, size, bold, Layout.Alignment.ALIGN_NORMAL);
            float h2 = text(left, margin, lw, size, bold, Layout.Alignment.ALIGN_OPPOSITE);
            y += Math.max(h1, h2) + 4;
        }

        /** صف جدول الأصناف: الاسم (يمين) | الكمية (وسط) | المبلغ (يسار). */
        void itemRow(String name, String qty, String total, boolean bold, String note) {
            int cw = cw();
            int tw = (int) (cw * 0.30f);
            int qw = (int) (cw * 0.15f);
            int nw = cw - tw - qw - 8;
            float nameX = margin + tw + qw + 8;
            float h1 = text(name, nameX, nw, base, bold, Layout.Alignment.ALIGN_NORMAL);
            float h2 = text(qty, margin + tw, qw, base, bold, Layout.Alignment.ALIGN_CENTER);
            float h3 = text(total, margin, tw, base, bold, Layout.Alignment.ALIGN_OPPOSITE);
            y += Math.max(h1, Math.max(h2, h3)) + 4;
            if (notEmpty(note)) {
                y += text("• " + note.trim(), nameX, nw, base * 0.8f, false, Layout.Alignment.ALIGN_NORMAL) + 4;
            }
        }

        void rule() {
            if (canvas != null) {
                Paint p = new Paint();
                p.setColor(Color.BLACK);
                p.setStrokeWidth(2f);
                canvas.drawLine(margin, y + 4, width - margin, y + 4, p);
            }
            y += 12;
        }

        void edgeMarks() {
            if (canvas != null) {
                Paint p = new Paint();
                p.setColor(Color.BLACK);
                p.setStrokeWidth(3f);
                canvas.drawLine(margin, y, margin, y + 26, p);
                canvas.drawLine(width - margin, y, width - margin, y + 26, p);
                canvas.drawLine(margin, y + 13, width - margin, y + 13, p);
            }
            y += 32;
        }

        void space(float h) {
            y += h;
        }
    }
}

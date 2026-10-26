package iq.haji.cashier.print;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import java.util.List;
import java.util.Map;

import iq.haji.cashier.data.AppSettings;
import iq.haji.cashier.model.Order;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.util.Fmt;

/**
 * تقارير السائقين للطابعة الحرارية: وردية سائق واحد، أو تقرير بكل حركات السائقين.
 * التقرير يُرسم كصورة بنفس عرض الوصل ويُعرض على الشاشة قبل الطباعة.
 */
public class DriverReportPrinter {

    public interface Callback {
        void onResult(boolean success, String message);
    }

    private static final int MAX_HEIGHT = 16000;
    private static final float[] COLS = {1.2f, 1.1f, 1.6f, 1.2f}; // الفاتورة | الوقت | المبلغ | التوصيل

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public DriverReportPrinter(Context context) {
        this.context = context.getApplicationContext();
    }

    /** صورة تقرير سائق واحد. */
    public Bitmap renderDriver(Shift shift, String driver, List<Order> orders) throws PrintException {
        AppSettings st = new AppSettings(context);
        return render(st, s -> {
            header(s, st, "وردية السائق", shift);
            driverBlock(s, driver, orders);
            footer(s);
        });
    }

    /** صورة تقرير كل السائقين (كل الحركات) مع الطلبات غير المنسوبة. */
    public Bitmap renderAll(Shift shift, Map<String, List<Order>> byDriver, List<Order> unassigned)
            throws PrintException {
        AppSettings st = new AppSettings(context);
        return render(st, s -> {
            header(s, st, "تقرير حركات السائقين", shift);
            int count = 0;
            long total = 0;
            long fees = 0;
            boolean first = true;
            for (Map.Entry<String, List<Order>> e : byDriver.entrySet()) {
                if (e.getValue().isEmpty()) {
                    continue;
                }
                if (!first) {
                    s.space(6);
                }
                first = false;
                driverBlock(s, e.getKey(), e.getValue());
                for (Order o : e.getValue()) {
                    count++;
                    total += o.total;
                    fees += o.deliveryFee;
                }
            }
            if (first) {
                s.center("لا توجد طلبات منسوبة لأي سائق", 0.9f, false);
            }
            s.space(6);
            s.doubleRule();
            s.banner("الملخص");
            s.space(4);
            s.pair("طلبات السائقين:", Fmt.number(count), 1f, true);
            s.pair("مبالغ الطلبات:", Fmt.money(total), 1f, false);
            s.pair("أجور التوصيل:", Fmt.money(fees), 1f, false);
            s.pair("طلبات بلا سائق:", Fmt.number(unassigned.size()), 1f, true);
            if (!unassigned.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Order o : unassigned) {
                    if (sb.length() > 0) {
                        sb.append("   ");
                    }
                    sb.append('#').append(o.id);
                }
                s.right(sb.toString(), 0.85f, false);
            }
            footer(s);
        });
    }

    public void print(final Bitmap bitmap, final Callback cb) {
        PrinterManager.get(context).executor().execute(() -> {
            boolean ok = false;
            String msg;
            try {
                AppSettings st = new AppSettings(context);
                byte[] bytes = PrinterProtocols.current(st).encode(bitmap, 1,
                        st.getBool(AppSettings.PRINT_CUT, true));
                PrinterManager.get(context).send(bytes);
                ok = true;
                msg = "تم إرسال تقرير السائقين للطابعة";
            } catch (Exception e) {
                msg = e.getMessage() == null ? "تعذرت الطباعة" : e.getMessage();
            }
            final boolean r = ok;
            final String m = msg;
            main.post(() -> cb.onResult(r, m));
        });
    }

    // ---------------- الأجزاء ----------------

    private void header(Sheet s, AppSettings st, String title, Shift shift) {
        s.space(4);
        s.center(st.restaurantName(), 1.45f, true);
        s.space(4);
        s.banner(title);
        s.space(4);
        if (shift != null) {
            s.pair("الوردية:", "#" + shift.id, 0.95f, false);
            s.pair("بدأت:", Fmt.dateTime(shift.startedAt), 0.9f, false);
        }
        s.pair("وقت الطباعة:", Fmt.dateTime(System.currentTimeMillis()), 0.9f, false);
        s.doubleRule();
    }

    private void driverBlock(Sheet s, String driver, List<Order> orders) {
        s.banner("السائق: " + driver);
        s.space(3);
        s.row(new String[]{"الفاتورة", "الوقت", "المبلغ", "التوصيل"}, 0.85f, true);
        s.rule(2f);
        long total = 0;
        long fees = 0;
        int i = 0;
        for (Order o : orders) {
            if (i++ > 0) {
                s.dashed();
            }
            s.row(new String[]{"#" + o.id, Fmt.time(o.createdAt), Fmt.number(o.total), Fmt.number(o.deliveryFee)},
                    0.9f, false);
            total += o.total;
            fees += o.deliveryFee;
        }
        if (orders.isEmpty()) {
            s.center("لا توجد طلبات منسوبة", 0.9f, false);
        }
        s.rule(2f);
        s.pair("عدد الطلبات:", Fmt.number(orders.size()), 1f, true);
        s.pair("مجموع المبالغ:", Fmt.money(total), 1f, true);
        s.pair("أجور التوصيل:", Fmt.money(fees), 1f, false);
    }

    private void footer(Sheet s) {
        s.space(8);
        s.center("نهاية التقرير", 0.9f, true);
        s.space(12);
    }

    // ---------------- الرسم ----------------

    private interface Content {
        void build(Sheet s);
    }

    private Bitmap render(AppSettings st, Content content) throws PrintException {
        int paper = st.paperWidthMm();
        int width = paper == 58 ? 384 : 576;
        int custom = st.getInt(ReceiptRenderer.KEY_WIDTH_DOTS, 0);
        if (custom >= 200 && custom <= 832) {
            width = custom;
        }
        width = (width / 8) * 8;
        int margin = Math.max(4, Math.min(st.getInt(AppSettings.PRINT_MARGIN_DOTS, 8), 60));
        float scale = st.getInt(AppSettings.PRINT_FONT_SCALE, 1) == 2 ? 1.3f : 1f;
        float base = (paper == 58 ? 22f : 26f) * scale;
        try {
            Sheet measure = new Sheet(null, width, margin, base);
            content.build(measure);
            int height = (int) Math.ceil(measure.y) + 24;
            if (height > MAX_HEIGHT) {
                throw new PrintException("التقرير طويل جداً للطباعة دفعة واحدة");
            }
            Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(Color.WHITE);
            content.build(new Sheet(canvas, width, margin, base));
            return bmp;
        } catch (OutOfMemoryError e) {
            throw new PrintException("الذاكرة غير كافية لإنشاء صورة التقرير", e);
        }
    }

    private static final class Sheet {
        private final Canvas canvas;
        private final int width;
        private final int margin;
        private final float base;
        float y = 4;

        Sheet(Canvas canvas, int width, int margin, float base) {
            this.canvas = canvas;
            this.width = width;
            this.margin = margin;
            this.base = base;
        }

        int cw() {
            return width - 2 * margin;
        }

        TextPaint paint(float size, boolean bold, int color) {
            TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(color);
            p.setTextSize(base * size);
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            return p;
        }

        float text(String value, float x, float top, int w, float size, boolean bold, int color,
                   Layout.Alignment a) {
            if (value == null || value.isEmpty() || w <= 0) {
                return 0;
            }
            StaticLayout l = StaticLayout.Builder.obtain(value, 0, value.length(), paint(size, bold, color), w)
                    .setAlignment(a)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .setIncludePad(false)
                    .build();
            if (canvas != null) {
                canvas.save();
                canvas.translate(x, top);
                l.draw(canvas);
                canvas.restore();
            }
            return l.getHeight();
        }

        void center(String v, float size, boolean bold) {
            y += text(v, margin, y, cw(), size, bold, Color.BLACK, Layout.Alignment.ALIGN_CENTER) + 3;
        }

        void right(String v, float size, boolean bold) {
            y += text(v, margin, y, cw(), size, bold, Color.BLACK, Layout.Alignment.ALIGN_NORMAL) + 3;
        }

        void pair(String label, String value, float size, boolean bold) {
            TextPaint p = paint(size, bold, Color.BLACK);
            int vw = Math.min((int) Math.ceil(p.measureText(value == null ? "" : value)) + 4, (int) (cw() * 0.6f));
            int gap = 8;
            float h1 = text(label, margin + vw + gap, y, cw() - vw - gap, size, bold, Color.BLACK,
                    Layout.Alignment.ALIGN_NORMAL);
            float h2 = text(value, margin, y, vw, size, bold, Color.BLACK, Layout.Alignment.ALIGN_OPPOSITE);
            y += Math.max(h1, h2) + 4;
        }

        void row(String[] cells, float size, boolean bold) {
            float sum = 0;
            for (float w : COLS) {
                sum += w;
            }
            int gap = 4;
            int usable = cw() - gap * (cells.length - 1);
            float x = width - margin;
            float h = 0;
            for (int i = 0; i < cells.length; i++) {
                int w = (int) (usable * COLS[i] / sum);
                x -= w;
                Layout.Alignment a = i == 0 ? Layout.Alignment.ALIGN_NORMAL
                        : (i == cells.length - 1 ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_CENTER);
                h = Math.max(h, text(cells[i], x, y, w, size, bold, Color.BLACK, a));
                x -= gap;
            }
            y += h + 3;
        }

        void banner(String v) {
            float pad = 5;
            float top = y;
            float h = text(v, margin, top + pad, cw(), 1.05f, true, Color.WHITE, Layout.Alignment.ALIGN_CENTER);
            if (canvas != null) {
                Paint bg = new Paint();
                bg.setColor(Color.BLACK);
                canvas.drawRect(margin, top, width - margin, top + h + pad * 2, bg);
                text(v, margin, top + pad, cw(), 1.05f, true, Color.WHITE, Layout.Alignment.ALIGN_CENTER);
            }
            y = top + h + pad * 2 + 3;
        }

        void line(float yy, float stroke, boolean dashed) {
            if (canvas == null) {
                return;
            }
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.BLACK);
            p.setStrokeWidth(stroke);
            if (dashed) {
                p.setStyle(Paint.Style.STROKE);
                p.setPathEffect(new DashPathEffect(new float[]{6f, 5f}, 0f));
            }
            canvas.drawLine(margin, yy, width - margin, yy, p);
        }

        void rule(float stroke) {
            line(y + 3, stroke, false);
            y += 9;
        }

        void doubleRule() {
            line(y + 2, 2f, false);
            line(y + 7, 2f, false);
            y += 13;
        }

        void dashed() {
            line(y + 3, 1f, true);
            y += 7;
        }

        void space(float h) {
            y += h;
        }
    }
}

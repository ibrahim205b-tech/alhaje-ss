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

import java.util.Calendar;
import java.util.List;

import iq.haji.cashier.data.AppSettings;
import iq.haji.cashier.data.DailySalesReportRepository;
import iq.haji.cashier.model.Shift;
import iq.haji.cashier.util.Fmt;

public class DailySalesReportPrinter {

    public interface Callback {
        void onResult(boolean success, String message);
    }

    private static final int MAX_HEIGHT = 16000;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    public DailySalesReportPrinter(Context context) {
        this.context = context.getApplicationContext();
    }

    public void printToday(Callback callback) {
        Calendar start = Calendar.getInstance();
        start.set(Calendar.HOUR_OF_DAY, 0);
        start.set(Calendar.MINUTE, 0);
        start.set(Calendar.SECOND, 0);
        start.set(Calendar.MILLISECOND, 0);

        long from = start.getTimeInMillis();

        Calendar end = (Calendar) start.clone();
        end.add(Calendar.DAY_OF_MONTH, 1);
        long to = end.getTimeInMillis();

        print(from, to, new Labels(), callback);
    }

    /**
     * يطبع تقرير وردية محددة (الحالية أو سابقة). وردية واحدة تكون مفتوحة في أي وقت،
     * لذلك طلبات الوردية هي الطلبات بين بدايتها ونهايتها.
     */
    public void printShift(Shift shift, Callback callback) {
        long from = shift.startedAt;
        long to = (shift.closed && shift.endedAt > 0 ? shift.endedAt : System.currentTimeMillis()) + 1;

        Labels l = new Labels();
        l.title = "تقرير الوردية";
        l.summary = "ملخص الوردية";
        l.net = "صافي مبيعات الوردية";
        l.footer = "نهاية تقرير الوردية";
        l.empty = "ماكو مبيعات مسجلة في هذه الوردية";
        l.noDrivers = "لا توجد طلبات منسوبة لسائق في هذه الوردية";
        l.sent = "تم إرسال تقرير الوردية " + Fmt.date(shift.startedAt) + " للطابعة";
        l.header.add(new String[]{"التاريخ:", Fmt.date(shift.startedAt)});
        l.header.add(new String[]{"بدأت:", Fmt.dateTime(shift.startedAt)});
        l.header.add(new String[]{"انتهت:", shift.closed && shift.endedAt > 0
                ? Fmt.dateTime(shift.endedAt) : "ما زالت مفتوحة"});
        if (shift.cashierName != null && !shift.cashierName.trim().isEmpty()) {
            l.header.add(new String[]{"الموظف:", shift.cashierName.trim()});
        }
        if (shift.closed) {
            l.header.add(new String[]{"افتتاح الصندوق:", Fmt.money(shift.openingCash)});
            l.header.add(new String[]{"الصندوق عند الإغلاق:", Fmt.money(shift.closingCash)});
        }
        print(from, to, l, callback);
    }

    /** نصوص التقرير: يومي افتراضياً، وتتغير لتقرير الوردية. */
    private static final class Labels {
        String title = "تقرير مبيعات اليوم";
        String summary = "ملخص اليوم";
        String net = "صافي مبيعات اليوم";
        String footer = "نهاية تقرير اليوم";
        String empty = "ماكو مبيعات مسجلة لليوم حتى تنطبع";
        String noDrivers = "لا توجد طلبات منسوبة لسائق اليوم";
        String sent = "تم إرسال تقرير مبيعات اليوم للطابعة";
        /** أسطر الرأس؛ إذا كانت فارغة يُطبع تاريخ اليوم. */
        final List<String[]> header = new java.util.ArrayList<>();
    }

    private void print(long from, long to, Labels labels, Callback callback) {
        PrinterManager.get(context).executor().execute(() -> {
            Bitmap bitmap = null;
            boolean success = false;
            String message;

            try {
                DailySalesReportRepository repository =
                        new DailySalesReportRepository(context);

                DailySalesReportRepository.Report report =
                        repository.load(from, to);

                if (report.orderCount == 0 || report.categories.isEmpty()) {
                    throw new PrintException(labels.empty);
                }

                AppSettings settings = new AppSettings(context);
                bitmap = render(report, settings, labels);

                byte[] bytes = PrinterProtocols.current(settings).encode(
                        bitmap,
                        1,
                        settings.getBool(AppSettings.PRINT_CUT, true)
                );

                PrinterManager.get(context).send(bytes);
                success = true;
                message = labels.sent;

            } catch (Exception e) {
                message = e.getMessage() == null
                        ? "تعذرت طباعة التقرير"
                        : e.getMessage();
            } finally {
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
            }

            final boolean result = success;
            final String resultMessage = message;
            main.post(() -> {
                if (callback != null) {
                    callback.onResult(result, resultMessage);
                }
            });
        });
    }

    private Bitmap render(
            DailySalesReportRepository.Report report,
            AppSettings settings,
            Labels labels
    ) throws PrintException {

        int paper = settings.paperWidthMm();
        int width = paper == 58 ? 384 : 576;

        int customWidth = settings.getInt(ReceiptRenderer.KEY_WIDTH_DOTS, 0);
        if (customWidth >= 200 && customWidth <= 832) {
            width = customWidth;
        }
        width = (width / 8) * 8;

        int margin = Math.max(4,
                Math.min(settings.getInt(AppSettings.PRINT_MARGIN_DOTS, 8), 60));

        float scale = settings.getInt(AppSettings.PRINT_FONT_SCALE, 1) == 2
                ? 1.3f : 1f;

        float base = (paper == 58 ? 22f : 26f) * scale;

        try {
            // القياس أولاً لمعرفة الارتفاع، ثم الرسم الفعلي بنفس الخطوات.
            Sheet measure = new Sheet(null, width, margin, base);
            build(measure, report, settings, labels);

            int height = (int) Math.ceil(measure.y) + 24;
            if (height > MAX_HEIGHT) {
                throw new PrintException(
                        "التقرير طويل جداً للطباعة دفعة واحدة. قلل عدد الأصناف أو اطبع الفترة على أجزاء");
            }

            Bitmap bitmap = Bitmap.createBitmap(
                    width, height, Bitmap.Config.ARGB_8888);

            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.WHITE);

            Sheet draw = new Sheet(canvas, width, margin, base);
            build(draw, report, settings, labels);

            return bitmap;

        } catch (OutOfMemoryError e) {
            throw new PrintException(
                    "الذاكرة غير كافية لإنشاء صورة التقرير", e);
        }
    }

    private void build(
            Sheet s,
            DailySalesReportRepository.Report report,
            AppSettings settings,
            Labels labels
    ) {
        float base = s.base;

        // ---------------- رأس التقرير ----------------
        s.space(4);
        s.center(settings.restaurantName(), base * 1.45f, true);
        s.space(4);
        s.banner(labels.title, base * 1.1f);
        s.space(6);

        if (labels.header.isEmpty()) {
            s.pair("التاريخ:", Fmt.date(report.from), base * 0.95f, false);
        }
        for (String[] line : labels.header) {
            s.pair(line[0], line[1], base * 0.95f, false);
        }
        s.pair("وقت الطباعة:", Fmt.time(System.currentTimeMillis()),
                base * 0.95f, false);
        s.pair("عدد الفواتير:", Fmt.number(report.orderCount),
                base * 0.95f, true);
        s.pair("عدد الأقسام:", Fmt.number(report.categories.size()),
                base * 0.95f, false);

        s.space(4);
        s.doubleRule();

        // ---------------- الأقسام ----------------
        for (DailySalesReportRepository.CategorySale category
                : report.categories) {

            s.space(6);
            s.banner("القسم: " + category.name, base * 1.05f);
            s.space(4);

            s.tableRow("الصنف", "العدد", "السعر", "المجموع",
                    base * 0.85f, true);
            s.rule(2f);

            int index = 0;
            for (DailySalesReportRepository.ItemSale item : category.items) {
                if (index > 0) {
                    s.dashedRule();
                }

                s.tableRow(
                        item.name,
                        Fmt.number(item.quantity),
                        Fmt.number(item.unitPrice),
                        Fmt.number(item.total),
                        base * 0.9f,
                        false
                );

                s.note("عدد الطلبات: " + Fmt.number(item.orderCount),
                        base * 0.72f);

                index++;
            }

            s.rule(2f);
            s.pair("إجمالي القسم:", Fmt.money(category.total),
                    base * 1.0f, true);
            s.space(4);
        }

        // ---------------- السائقون ----------------
        s.doubleRule();
        s.space(4);
        s.banner("السائقون", base * 1.05f);
        s.space(6);

        if (report.drivers.isEmpty()) {
            s.center(labels.noDrivers,
                    base * 0.9f, false);
            s.space(4);
        }

        int driverIndex = 0;
        for (DailySalesReportRepository.DriverSale driver : report.drivers) {
            if (driverIndex > 0) {
                s.dashedRule();
            }

            s.pair(driver.name,
                    Fmt.number(driver.orderIds.size()) + " طلب",
                    base * 1.0f, true);
            s.note("أرقام الفواتير:", base * 0.8f);

            // أرقام الفواتير في أعمدة، أربعة أرقام في كل سطر.
            List<Long> ids = driver.orderIds;
            for (int i = 0; i < ids.size(); i += 4) {
                String[] cells = new String[4];
                for (int j = 0; j < 4; j++) {
                    cells[j] = i + j < ids.size()
                            ? "#" + ids.get(i + j) : "";
                }
                s.gridRow(cells, base * 0.9f);
            }

            s.space(4);
            driverIndex++;
        }

        s.space(4);

        // ---------------- الملخص ----------------
        s.doubleRule();
        s.space(4);
        s.banner(labels.summary, base * 1.05f);
        s.space(6);

        s.pair("عدد الفواتير:", Fmt.number(report.orderCount),
                base * 0.95f, false);
        s.pair("إجمالي مبيعات الأصناف:", Fmt.money(report.itemTotal),
                base * 0.95f, false);

        long difference = report.invoiceTotal - report.itemTotal;
        if (difference != 0) {
            s.pair("الخصم والإضافات:",
                    (difference > 0 ? "+ " : "- ")
                            + Fmt.money(Math.abs(difference)),
                    base * 0.95f, false);
        }

        s.space(4);
        s.boxStart();
        s.center(labels.net, base * 0.95f, true);
        s.center(Fmt.money(report.invoiceTotal), base * 1.45f, true);
        s.boxEnd();

        // ---------------- التذييل ----------------
        s.space(8);
        s.center(labels.footer, base * 0.9f, true);
        s.center(Fmt.dateTime(System.currentTimeMillis()),
                base * 0.75f, false);
        s.space(12);
    }

    /** ورقة رسم بسيطة: عند canvas == null تقيس الارتفاع فقط. */
    private static final class Sheet {
        private final Canvas canvas;
        private final int width;
        private final int margin;
        final float base;
        float y = 4;

        private float boxTop = -1;

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
            p.setTextSize(size);
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            return p;
        }

        float text(
                String value,
                float x,
                float top,
                int w,
                float size,
                boolean bold,
                int color,
                Layout.Alignment alignment
        ) {
            if (value == null || value.isEmpty() || w <= 0) {
                return 0;
            }

            StaticLayout layout = StaticLayout.Builder.obtain(
                            value, 0, value.length(),
                            paint(size, bold, color), w)
                    .setAlignment(alignment)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1f)
                    .build();

            if (canvas != null) {
                canvas.save();
                canvas.translate(x, top);
                layout.draw(canvas);
                canvas.restore();
            }

            return layout.getHeight();
        }

        void center(String value, float size, boolean bold) {
            y += text(value, margin, y, cw(), size, bold, Color.BLACK,
                    Layout.Alignment.ALIGN_CENTER) + 3;
        }

        void note(String value, float size) {
            int w = (int) (cw() * 0.58f);
            y += text(value, width - margin - w, y, w, size, false,
                    Color.BLACK, Layout.Alignment.ALIGN_NORMAL) + 3;
        }

        /** شريط أسود بعرض الورقة ونص أبيض في الوسط لعناوين الأقسام. */
        void banner(String value, float size) {
            float pad = 6;
            float top = y;
            float h = text(value, margin, top + pad, cw(), size, true,
                    Color.WHITE, Layout.Alignment.ALIGN_CENTER);

            // الرسم مرتين: الخلفية أولاً ثم النص فوقها.
            if (canvas != null) {
                Paint bg = new Paint();
                bg.setColor(Color.BLACK);
                bg.setStyle(Paint.Style.FILL);
                canvas.drawRect(margin, top, width - margin,
                        top + h + pad * 2, bg);

                text(value, margin, top + pad, cw(), size, true,
                        Color.WHITE, Layout.Alignment.ALIGN_CENTER);
            }

            y = top + h + pad * 2 + 2;
        }

        /** عنوان على اليمين وقيمة على اليسار في سطر واحد. */
        void pair(String right, String left, float size, boolean bold) {
            TextPaint p = paint(size, bold, Color.BLACK);

            int leftWidth = (int) Math.ceil(
                    p.measureText(left == null ? "" : left)) + 4;
            leftWidth = Math.min(leftWidth, (int) (cw() * 0.55f));

            int gap = 8;
            int rightWidth = cw() - leftWidth - gap;

            float rh = text(right, margin + leftWidth + gap, y, rightWidth,
                    size, bold, Color.BLACK, Layout.Alignment.ALIGN_NORMAL);
            float lh = text(left, margin, y, leftWidth,
                    size, bold, Color.BLACK, Layout.Alignment.ALIGN_OPPOSITE);

            y += Math.max(rh, lh) + 4;
        }

        /** صف جدول: الصنف (يمين) | العدد | السعر | المجموع (يسار). */
        void tableRow(
                String name,
                String qty,
                String price,
                String total,
                float size,
                boolean bold
        ) {
            int c = cw();
            int totalW = (int) (c * 0.27f);
            int priceW = (int) (c * 0.23f);
            int qtyW = (int) (c * 0.12f);
            int gap = 4;
            int nameW = c - totalW - priceW - qtyW - gap * 3;

            float xTotal = margin;
            float xPrice = xTotal + totalW + gap;
            float xQty = xPrice + priceW + gap;
            float xName = xQty + qtyW + gap;

            float hName = text(name, xName, y, nameW, size, bold,
                    Color.BLACK, Layout.Alignment.ALIGN_NORMAL);
            float hQty = text(qty, xQty, y, qtyW, size, bold,
                    Color.BLACK, Layout.Alignment.ALIGN_CENTER);
            float hPrice = text(price, xPrice, y, priceW, size, bold,
                    Color.BLACK, Layout.Alignment.ALIGN_CENTER);
            float hTotal = text(total, xTotal, y, totalW, size, bold,
                    Color.BLACK, Layout.Alignment.ALIGN_OPPOSITE);

            y += Math.max(Math.max(hName, hQty),
                    Math.max(hPrice, hTotal)) + 3;
        }

        /** صف من خلايا متساوية العرض، يبدأ من اليمين. */
        void gridRow(String[] cells, float size) {
            int count = cells.length;
            int cellW = cw() / count;
            float h = 0;

            for (int i = 0; i < count; i++) {
                float x = width - margin - cellW * (i + 1);
                h = Math.max(h, text(cells[i], x, y, cellW, size, true,
                        Color.BLACK, Layout.Alignment.ALIGN_CENTER));
            }

            y += h + 3;
        }

        void rule(float stroke) {
            if (canvas != null) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.BLACK);
                p.setStrokeWidth(stroke);
                canvas.drawLine(margin, y + 3, width - margin, y + 3, p);
            }
            y += 8;
        }

        void doubleRule() {
            if (canvas != null) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.BLACK);
                p.setStrokeWidth(2f);
                canvas.drawLine(margin, y + 2, width - margin, y + 2, p);
                canvas.drawLine(margin, y + 7, width - margin, y + 7, p);
            }
            y += 12;
        }

        void dashedRule() {
            if (canvas != null) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.BLACK);
                p.setStrokeWidth(1f);
                p.setStyle(Paint.Style.STROKE);
                p.setPathEffect(new DashPathEffect(new float[]{6f, 5f}, 0f));
                canvas.drawLine(margin, y + 2, width - margin, y + 2, p);
            }
            y += 6;
        }

        /** إطار حول ملخص الإجمالي النهائي. */
        void boxStart() {
            boxTop = y;
            y += 8;
        }

        void boxEnd() {
            y += 4;
            if (canvas != null && boxTop >= 0) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.BLACK);
                p.setStrokeWidth(3f);
                p.setStyle(Paint.Style.STROKE);
                canvas.drawRect(margin + 1, boxTop,
                        width - margin - 1, y, p);
            }
            boxTop = -1;
            y += 6;
        }

        void space(float h) {
            y += h;
        }
    }
}


package iq.haji.cashier.print;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import iq.haji.cashier.data.AppSettings;
import iq.haji.cashier.data.DailySalesReportRepository;
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
                    throw new PrintException("ماكو مبيعات مسجلة لليوم حتى تنطبع");
                }

                AppSettings settings = new AppSettings(context);
                bitmap = render(report, settings);

                byte[] bytes = PrinterProtocols.current(settings).encode(
                        bitmap,
                        1,
                        settings.getBool(AppSettings.PRINT_CUT, true)
                );

                PrinterManager.get(context).send(bytes);
                success = true;
                message = "تم إرسال تقرير مبيعات اليوم للطابعة";

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
            AppSettings settings
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

        List<Line> lines = buildLines(report, settings, base);

        int usableWidth = width - margin * 2;

        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);

        float measuredHeight = 12;
        for (Line line : lines) {
            paint.setTextSize(line.size);
            paint.setTypeface(line.bold
                    ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

            StaticLayout layout = StaticLayout.Builder.obtain(
                            line.text, 0, line.text.length(), paint, usableWidth)
                    .setAlignment(line.center
                            ? Layout.Alignment.ALIGN_CENTER
                            : Layout.Alignment.ALIGN_NORMAL)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .setIncludePad(false)
                    .build();

            measuredHeight += layout.getHeight() + line.after;
        }

        int height = (int) Math.ceil(measuredHeight + 24);
        if (height > MAX_HEIGHT) {
            throw new PrintException(
                    "التقرير طويل جداً للطباعة دفعة واحدة. قلل عدد الأصناف أو اطبع الفترة على أجزاء");
        }

        Bitmap bitmap = Bitmap.createBitmap(
                width, height, Bitmap.Config.ARGB_8888);

        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);

        float y = 8;
        for (Line line : lines) {
            paint.setTextSize(line.size);
            paint.setTypeface(line.bold
                    ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

            StaticLayout layout = StaticLayout.Builder.obtain(
                            line.text, 0, line.text.length(), paint, usableWidth)
                    .setAlignment(line.center
                            ? Layout.Alignment.ALIGN_CENTER
                            : Layout.Alignment.ALIGN_NORMAL)
                    .setTextDirection(TextDirectionHeuristics.RTL)
                    .setIncludePad(false)
                    .build();

            canvas.save();
            canvas.translate(margin, y);
            layout.draw(canvas);
            canvas.restore();

            y += layout.getHeight() + line.after;
        }

        return bitmap;
    }

    private List<Line> buildLines(
            DailySalesReportRepository.Report report,
            AppSettings settings,
            float base
    ) {
        List<Line> lines = new ArrayList<>();

        add(lines, settings.restaurantName(), base * 1.45f, true, true, 5);
        add(lines, "تقرير مبيعات اليوم", base * 1.15f, true, true, 4);
        add(lines, "التاريخ: " + Fmt.date(report.from), base, false, true, 4);
        add(lines, "عدد الفواتير: " + Fmt.number(report.orderCount),
                base, true, true, 4);
        rule(lines, base);

        for (DailySalesReportRepository.CategorySale category
                : report.categories) {

            add(lines, "القسم: " + category.name,
                    base * 1.15f, true, true, 5);
            rule(lines, base);

            for (DailySalesReportRepository.ItemSale item : category.items) {
                add(lines, item.name, base * 1.05f, true, false, 3);
                add(lines,
                        "سعر القطعة: " + Fmt.money(item.unitPrice),
                        base * 0.9f, false, false, 3);
                add(lines,
                        "عدد الطلبات: " + Fmt.number(item.orderCount)
                                + " | القطع: " + Fmt.number(item.quantity),
                        base * 0.9f, false, false, 3);
                add(lines,
                        "إجمالي الصنف: " + Fmt.money(item.total),
                        base, true, false, 5);
            }

            rule(lines, base);
            add(lines,
                    "إجمالي القسم: " + Fmt.money(category.total),
                    base * 1.05f, true, false, 8);
            rule(lines, base);
        }

        add(lines, "إجمالي مبيعات الأصناف: " + Fmt.money(report.itemTotal),
                base, true, false, 5);
        add(lines, "إجمالي الفواتير بعد الخصم والإضافات: "
                        + Fmt.money(report.invoiceTotal),
                base * 1.15f, true, false, 8);
        rule(lines, base);
        add(lines, "نهاية تقرير اليوم", base, true, true, 12);

        return lines;
    }

    private void add(
            List<Line> lines,
            String text,
            float size,
            boolean bold,
            boolean center,
            float after
    ) {
        lines.add(new Line(text, size, bold, center, after));
    }

    private void rule(List<Line> lines, float base) {
        add(lines, "--------------------------------", base * 0.75f,
                false, true, 5);
    }

    private static class Line {
        final String text;
        final float size;
        final boolean bold;
        final boolean center;
        final float after;

        Line(String text, float size, boolean bold, boolean center, float after) {
            this.text = text;
            this.size = size;
            this.bold = bold;
            this.center = center;
            this.after = after;
        }
    }
}

package iq.haji.cashier.print;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.atomic.AtomicBoolean;

import iq.haji.cashier.data.AppSettings;
import iq.haji.cashier.data.OrderRepository;
import iq.haji.cashier.model.Order;
import iq.haji.cashier.model.OrderStatus;
import iq.haji.cashier.util.AppException;

/**
 * ينسّق «طباعة الفاتورة»: يحفظ الطلب عبر OrderRepository، ثم يطبع عبر PrinterManager، ثم يؤكد الحالة.
 * القواعد:
 *  - الطلب يُحفظ «بانتظار الطباعة» قبل أي إرسال، فلا يضيع إذا فشلت الطباعة.
 *  - إعادة المحاولة بنفس كائن Order (أو reprint برقمه) تعيد الرقم نفسه ولا تنشئ فاتورة جديدة.
 *  - عملية طباعة واحدة فقط في كل مرة (يمنع الضغط المزدوج).
 *  - المسودة لا تُمسح ولا يُغيَّر محتوى الطلب عند الفشل؛ الشاشة تمسح الطلب الجاري فقط عند result.success.
 * كل النتائج تُسلَّم على خيط الواجهة.
 */
public class ReceiptPrinter {

    public static class Result {
        public boolean success;
        public boolean orderSaved;   // هل حُفظ الطلب (له رقم) حتى لو فشلت الطباعة
        public long orderId;
        public String message;
    }

    public interface Callback {
        void onResult(Result r);
    }

    private interface Job {
        Result run() throws Exception;
    }

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());

    public ReceiptPrinter(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public static boolean isBusy() {
        return BUSY.get();
    }

    // ---------------- نقاط الدخول ----------------

    /** زر «طباعة الفاتورة» في شاشة الكاشير. o هو الطلب الجاري؛ يتحدث o.id بعد الحفظ فتصلح إعادة المحاولة بنفس الكائن. */
    public void printOrder(final Order o, final Callback cb) {
        submit(new Job() {
            @Override
            public Result run() {
                OrderRepository repo = new OrderRepository(ctx);
                long id;
                try {
                    id = repo.placeOrderForPrint(o);
                } catch (AppException e) {
                    return fail(o.id, o.id != 0, e.getMessage());
                }
                Order saved = repo.getOrder(id);
                if (saved == null) return fail(id, false, "تعذر قراءة الطلب بعد حفظه");
                return sendSaved(repo, saved);
            }
        }, cb);
    }

    /** إعادة طباعة/إعادة محاولة لطلب محفوظ (من شاشة الطلبات أو تفاصيل الطلب). */
    public void reprint(final long orderId, final Callback cb) {
        submit(new Job() {
            @Override
            public Result run() {
                OrderRepository repo = new OrderRepository(ctx);
                Order saved = repo.getOrder(orderId);
                if (saved == null) return fail(orderId, false, "الطلب غير موجود");
                return sendSaved(repo, saved);
            }
        }, cb);
    }

    /** صفحة اختبار: لا تلمس أي طلب. */
    public void printTest(final Callback cb) {
        submit(new Job() {
            @Override
            public Result run() {
                PrinterManager pm = PrinterManager.get(ctx);
                AppSettings st = new AppSettings(ctx);
                Bitmap bmp = null;
                try {
                    bmp = new ReceiptRenderer(ctx).renderTest(pm.describeSelected());
                    byte[] data = PrinterProtocols.current(st).encode(bmp, 1, st.getBool(AppSettings.PRINT_CUT, true));
                    pm.send(data);
                } catch (PrintException e) {
                    return fail(0, false, "فشل اختبار الطباعة: " + e.getMessage());
                } finally {
                    if (bmp != null) bmp.recycle();
                }
                Result r = new Result();
                r.success = true;
                r.message = "أُرسلت صفحة الاختبار إلى الطابعة.\n" + PrinterManager.DELIVERY_NOTE;
                return r;
            }
        }, cb);
    }

    // ---------------- الداخل ----------------

    private Result sendSaved(OrderRepository repo, Order saved) {
        long id = saved.id;
        if (OrderStatus.CANCELLED.equals(saved.status)) {
            return fail(id, true, "الطلب رقم " + id + " ملغى ولا يمكن طباعته");
        }
        AppSettings st = new AppSettings(ctx);
        PrinterManager pm = PrinterManager.get(ctx);
        Bitmap bmp = null;
        try {
            bmp = new ReceiptRenderer(ctx).render(saved);
            byte[] data = PrinterProtocols.current(st)
                    .encode(bmp, st.copies(), st.getBool(AppSettings.PRINT_CUT, true));
            pm.send(data);
        } catch (PrintException e) {
            repo.recordPrintFailure(id, e.getMessage());
            return fail(id, true, "فشلت الطباعة: " + e.getMessage()
                    + "\nالطلب رقم " + id + " محفوظ بكامل محتوياته. اضغط «إعادة المحاولة» بعد إصلاح المشكلة.");
        } catch (RuntimeException e) {
            repo.recordPrintFailure(id, String.valueOf(e.getMessage()));
            return fail(id, true, "خطأ غير متوقع أثناء الطباعة. الطلب رقم " + id
                    + " محفوظ، ويمكنك إعادة المحاولة.");
        } finally {
            if (bmp != null) bmp.recycle();
        }

        Result r = new Result();
        r.success = true;
        r.orderSaved = true;
        r.orderId = id;
        try {
            repo.markPrinted(id);
            r.message = "تم إرسال الفاتورة رقم " + id + " إلى الطابعة.\n" + PrinterManager.DELIVERY_NOTE;
        } catch (RuntimeException e) {
            r.message = "طُبعت الفاتورة رقم " + id + " لكن تعذر تحديث حالتها. افتح الطلب من شاشة الطلبات "
                    + "ولا تعد الطباعة حتى لا تتكرر الفاتورة.";
        }
        return r;
    }

    private static Result fail(long id, boolean saved, String msg) {
        Result r = new Result();
        r.success = false;
        r.orderSaved = saved;
        r.orderId = id;
        r.message = msg;
        return r;
    }

    private void submit(final Job job, final Callback cb) {
        if (!BUSY.compareAndSet(false, true)) {
            post(cb, fail(0, false, "هناك عملية طباعة جارية. انتظر انتهاءها ثم أعد المحاولة."));
            return;
        }
        PrinterManager.get(ctx).executor().execute(new Runnable() {
            @Override
            public void run() {
                Result r;
                try {
                    r = job.run();
                } catch (Exception e) {
                    r = fail(0, false, "خطأ غير متوقع: " + e.getMessage());
                } finally {
                    BUSY.set(false);
                }
                post(cb, r);
            }
        });
    }

    private void post(final Callback cb, final Result r) {
        if (cb == null) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                cb.onResult(r);
            }
        });
    }
}

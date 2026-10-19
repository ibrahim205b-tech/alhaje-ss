package iq.haji.cashier.model;

import iq.haji.cashier.R;

public final class OrderStatus {
    public static final String NEW = "NEW";
    public static final String PENDING_PRINT = "PENDING_PRINT";
    public static final String PREPARING = "PREPARING";
    public static final String READY = "READY";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    private OrderStatus() {}

    public static String label(String s) {
        if (NEW.equals(s)) return "جديد";
        if (PENDING_PRINT.equals(s)) return "بانتظار الطباعة";
        if (PREPARING.equals(s)) return "قيد التجهيز";
        if (READY.equals(s)) return "جاهز للتسليم";
        if (COMPLETED.equals(s)) return "مكتمل";
        if (CANCELLED.equals(s)) return "ملغى";
        return s == null ? "" : s;
    }

    public static int colorRes(String s) {
        if (NEW.equals(s)) return R.color.status_new;
        if (PENDING_PRINT.equals(s)) return R.color.status_pending;
        if (PREPARING.equals(s)) return R.color.status_preparing;
        if (READY.equals(s)) return R.color.status_ready;
        if (COMPLETED.equals(s)) return R.color.status_done;
        if (CANCELLED.equals(s)) return R.color.status_cancelled;
        return R.color.text_secondary;
    }

    /** الحالات التي تُحسب ضمن المبيعات (لا مسودات ولا ملغاة). */
    public static boolean countsAsSale(String s) {
        return PREPARING.equals(s) || READY.equals(s) || COMPLETED.equals(s);
    }
}

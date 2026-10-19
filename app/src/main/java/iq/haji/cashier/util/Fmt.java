package iq.haji.cashier.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class Fmt {
    private Fmt() {}

    /** 12500 → "12,500 د.ع" (أرقام لاتينية لتفادي مشاكل الطابعات والأرقام الشرقية). */
    public static String money(long v) {
        return String.format(Locale.US, "%,d", v) + " د.ع";
    }

    public static String number(long v) {
        return String.format(Locale.US, "%,d", v);
    }

    public static String dateTime(long ms) {
        return new SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(new Date(ms));
    }

    public static String date(long ms) {
        return new SimpleDateFormat("yyyy/MM/dd", Locale.US).format(new Date(ms));
    }

    public static String time(long ms) {
        return new SimpleDateFormat("HH:mm", Locale.US).format(new Date(ms));
    }

    /** يحوّل الأرقام الشرقية (٠-٩) المكتوبة من لوحة المفاتيح العربية إلى لاتينية قبل التحليل. */
    public static String normalizeDigits(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            if (ch >= '\u0660' && ch <= '\u0669') sb.append((char) ('0' + (ch - '\u0660')));
            else if (ch >= '\u06F0' && ch <= '\u06F9') sb.append((char) ('0' + (ch - '\u06F0')));
            else if (ch == '\u066C' || ch == ',' || ch == '\u060C') continue; // فواصل الآلاف
            else sb.append(ch);
        }
        return sb.toString().trim();
    }

    /** تحليل مبلغ صحيح موجب. يعيد -1 عند الخطأ. */
    public static long parseMoney(String s) {
        String n = normalizeDigits(s);
        if (n.isEmpty()) return -1;
        try {
            long v = Long.parseLong(n);
            return v < 0 ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

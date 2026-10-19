package iq.haji.cashier.util;

/** توحيد النص العربي للبحث: يتجاهل التشكيل والتطويل ويوحّد (أ إ آ) و(ة ه) و(ى ي). */
public final class ArabicText {
    private ArabicText() {}

    public static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch >= '\u064B' && ch <= '\u065F') continue; // التشكيل
            if (ch == '\u0640') continue;                    // التطويل
            if (ch == '\u0623' || ch == '\u0625' || ch == '\u0622') ch = '\u0627';
            else if (ch == '\u0629') ch = '\u0647';
            else if (ch == '\u0649') ch = '\u064A';
            sb.append(Character.toLowerCase(ch));
        }
        return sb.toString().trim();
    }
}

package iq.haji.cashier.data;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** قائمة أسماء السائقين محفوظة على الجهاز (نفس مكان الحفظ السابق في الإعدادات). */
public final class DriverStore {

    private static final String PREFS = "haji_driver_data";
    private static final String KEY = "drivers";
    private static final String DEFAULT = "حسين\nأحمد\nعلي\nجبار";

    private DriverStore() {
    }

    public static List<String> load(Context c) {
        String saved = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT);
        List<String> out = new ArrayList<>();
        if (saved == null) {
            return out;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String line : saved.split("\\r?\\n")) {
            String n = line.trim();
            if (!n.isEmpty()) {
                unique.add(n);
            }
        }
        out.addAll(unique);
        return out;
    }

    public static void save(Context c, List<String> names) {
        StringBuilder sb = new StringBuilder();
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String n : names) {
            String t = n == null ? "" : n.trim();
            if (!t.isEmpty()) {
                unique.add(t);
            }
        }
        for (String n : unique) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(n);
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, sb.toString()).apply();
    }
}

package iq.haji.cashier.util;

import java.util.Calendar;

public final class DateUtil {
    private DateUtil() {}

    public static long startOfDay(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** بداية اليوم التالي (حد أعلى حصري للاستعلامات: created_at < هذا). */
    public static long startOfNextDay(long ms) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(startOfDay(ms));
        c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    public static long startOfDay(int year, int month0, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month0, day, 0, 0, 0);
        return c.getTimeInMillis();
    }
}

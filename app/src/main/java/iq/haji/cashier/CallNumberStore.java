package iq.haji.cashier;

import android.content.Context;
import android.content.SharedPreferences;

public final class CallNumberStore {

    private static final String PREFS = "haji_call_data";
    private static final String KEY_NUMBER = "latest_number";
    private static final String KEY_TIME = "latest_call_time";
    private static final String KEY_PENDING = "pending_phone";

    private CallNumberStore() {
    }

    public static void save(Context context, String phone) {
        if (context == null || phone == null
                || phone.trim().isEmpty()) {
            return;
        }

        String number = phone.trim();

        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        prefs.edit()
                .putString(KEY_NUMBER, number)
                .putLong(KEY_TIME, System.currentTimeMillis())
                .putString(KEY_PENDING, number)
                .apply();
    }

    public static String getLatest(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        String number = prefs.getString(KEY_NUMBER, "");

        return number == null ? "" : number;
    }

    public static long getLatestTime(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        return prefs.getLong(KEY_TIME, 0L);
    }

    public static String consume(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        String phone = prefs.getString(KEY_PENDING, "");

        prefs.edit()
                .remove(KEY_PENDING)
                .apply();

        return phone == null ? "" : phone;
    }

    public static void clearLatest(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        prefs.edit()
                .remove(KEY_NUMBER)
                .remove(KEY_TIME)
                .remove(KEY_PENDING)
                .apply();
    }
}

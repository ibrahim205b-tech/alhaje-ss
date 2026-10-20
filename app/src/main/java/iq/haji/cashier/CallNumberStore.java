package iq.haji.cashier;

import android.content.Context;
import android.content.SharedPreferences;

public final class CallNumberStore {

    private static final String PREFS = "haji_call_data";
    private static final String PENDING_PHONE = "pending_phone";

    private CallNumberStore() {}

    public static void save(Context context, String phone) {
        if (phone == null || phone.trim().isEmpty()) return;

        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        prefs.edit()
                .putString(PENDING_PHONE, phone.trim())
                .apply();
    }

    public static String consume(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        String phone = prefs.getString(PENDING_PHONE, "");

        prefs.edit().remove(PENDING_PHONE).apply();

        return phone == null ? "" : phone;
    }
}

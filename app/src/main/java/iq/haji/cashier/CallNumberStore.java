package iq.haji.cashier;

import android.content.Context;
import android.content.SharedPreferences;

public final class CallNumberStore {

    private static final String PREFS = "haji_call_data";
    private static final String KEY_NUMBER = "latest_number";
    private static final String KEY_TIME = "latest_call_time";
    private static final String KEY_PENDING = "pending_phone";
    private static final String KEY_RINGING = "ringing_number";
    private static final String KEY_RINGING_TIME = "ringing_time";
    private static final String KEY_LAST_STATE = "last_call_state";

    /** أقصى مدة بين رنين المكالمة والرد عليها حتى يُعتمد رقمها. */
    private static final long RINGING_MAX_AGE_MS = 3 * 60 * 1000L;

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

    /**
     * يحفظ رقم مكالمة ما زالت ترن دون أن يصل إلى الطلب.
     * لا يُنقل إلى خانة رقم الزبون إلا بعد الرد عبر {@link #promoteRinging}.
     */
    public static void saveRinging(Context context, String phone) {
        if (context == null || phone == null
                || phone.trim().isEmpty()) {
            return;
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RINGING, phone.trim())
                .putLong(KEY_RINGING_TIME, System.currentTimeMillis())
                .apply();
    }

    /** تم الرد على المكالمة: يصبح رقمها رقم الزبون. يرجع true إذا وُجد رقم. */
    public static boolean promoteRinging(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);

        String number = prefs.getString(KEY_RINGING, "");
        long time = prefs.getLong(KEY_RINGING_TIME, 0L);

        clearRinging(context);

        if (number == null || number.trim().isEmpty()
                || System.currentTimeMillis() - time > RINGING_MAX_AGE_MS) {
            return false;
        }

        save(context, number);
        return true;
    }

    /** مكالمة لم يُرد عليها أو انتهت: يُهمل رقمها. */
    public static void clearRinging(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_RINGING)
                .remove(KEY_RINGING_TIME)
                .apply();
    }

    public static String getLastState(Context context) {
        String state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_STATE, "");

        return state == null ? "" : state;
    }

    public static void setLastState(Context context, String state) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_STATE, state)
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

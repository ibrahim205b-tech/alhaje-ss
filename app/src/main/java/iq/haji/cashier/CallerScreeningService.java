package iq.haji.cashier;

import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallScreeningService;

public class CallerScreeningService extends CallScreeningService {

    private static final String PREFS = "haji_call_data";
    private static final String KEY_NUMBER = "latest_number";
    private static final String KEY_TIME = "latest_call_time";

    @Override
    public void onScreenCall(Call.Details details) {
        if (details == null) {
            allowCall(details);
            return;
        }

        Uri handle = details.getHandle();

        if (handle != null) {
            String number = handle.getSchemeSpecificPart();

            if (number != null && !number.trim().isEmpty()) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit()
                        .putString(KEY_NUMBER, number.trim())
                        .putLong(KEY_TIME, System.currentTimeMillis())
                        .apply();
            }
        }

        allowCall(details);
    }

    private void allowCall(Call.Details details) {
        if (details == null) {
            return;
        }

        CallResponse response = new CallResponse.Builder()
                .setDisallowCall(false)
                .setRejectCall(false)
                .setSilenceCall(false)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build();

        respondToCall(details, response);
    }
}

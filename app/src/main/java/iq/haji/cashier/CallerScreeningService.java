package iq.haji.cashier;

import android.net.Uri;
import android.telecom.Call;
import android.telecom.CallScreeningService;

public class CallerScreeningService extends CallScreeningService {

    @Override
    public void onScreenCall(Call.Details details) {
        if (details == null) {
            return;
        }

        try {
            Uri handle = details.getHandle();

            if (handle != null) {
                String number = handle.getSchemeSpecificPart();

                if (number != null && !number.trim().isEmpty()) {
                    CallNumberStore.save(this, number.trim());
                }
            }
        } finally {
            allowCall(details);
        }
    }

    private void allowCall(Call.Details details) {
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

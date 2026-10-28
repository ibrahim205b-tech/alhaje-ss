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
            // نلتقط رقم المكالمات الواردة فقط حتى لا يستبدل
            // اتصال الكاشير بالسائق رقم الزبون.
            if (details.getCallDirection() != Call.Details.DIRECTION_INCOMING) {
                return;
            }

            Uri handle = details.getHandle();

            if (handle != null) {
                String number = handle.getSchemeSpecificPart();

                if (number != null && !number.trim().isEmpty()) {
                    // يُحفظ الرقم معلقاً، ولا يدخل الطلب إلا بعد الرد على المكالمة.
                    CallNumberStore.saveRinging(this, number.trim());
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


package iq.haji.cashier;

import android.net.Uri;
import android.telecom.Call;
import android.telecom.InCallService;

public class HajiInCallService extends InCallService {

    public static volatile Call currentCall;

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);

        currentCall = call;

        if (call == null) {
            return;
        }

        Call.Details details = call.getDetails();

        if (details == null
                || call.getState() != Call.STATE_RINGING) {
            return;
        }

        Uri handle = details.getHandle();

        if (handle == null) {
            return;
        }

        String phone = handle.getSchemeSpecificPart();

        if (phone == null || phone.trim().isEmpty()) {
            return;
        }

        phone = phone.trim();

        // يبقى الرقم معلقاً، ولا يدخل الطلب إلا بعد الرد على المكالمة
        // (يتولى ذلك PhoneStateReceiver).
        CallNumberStore.saveRinging(this, phone);
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);

        if (currentCall == call) {
            currentCall = null;
        }
    }
}

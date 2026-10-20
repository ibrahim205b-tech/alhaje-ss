package iq.haji.cashier;

import android.telecom.Call;
import android.telecom.InCallService;

public class HajiInCallService extends InCallService {

    public static volatile Call currentCall;

    @Override
    public void onCallAdded(Call call) {
        super.onCallAdded(call);

        currentCall = call;

        Call.Details details = call.getDetails();

        if (details != null && details.getHandle() != null) {
            String phone = details.getHandle()
                    .getSchemeSpecificPart();

            if (call.getState() == Call.STATE_RINGING) {
                CallNumberStore.save(this, phone);
            }
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        super.onCallRemoved(call);

        if (currentCall == call) {
            currentCall = null;
        }
    }
}

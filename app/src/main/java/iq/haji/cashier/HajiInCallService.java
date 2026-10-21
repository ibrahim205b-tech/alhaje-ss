
package iq.haji.cashier;

import android.content.Intent;
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

        // Store the caller number temporarily for the current order.
        CallNumberStore.save(this, phone);

        // Notify the cashier screen using its existing intent handler.
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction("iq.haji.cashier.NEW_CALL_NUMBER");
        intent.putExtra("caller_number", phone);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
        );

        try {
            startActivity(intent);
        } catch (RuntimeException ignored) {
            // The number remains pending and can be applied
            // when the cashier screen resumes.
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

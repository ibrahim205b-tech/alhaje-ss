package iq.haji.cashier;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

/**
 * يراقب حالة الهاتف ليُدخل رقم المتصل في الطلب عند الرد على المكالمة فقط.
 * مكالمة ترن ثم تُرفض أو تفوت لا تغيّر رقم الزبون، والمكالمات الصادرة (للسائق مثلاً)
 * لا تمر بحالة الرنين فلا تُعتمد.
 */
public class PhoneStateReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null
                || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) {
            return;
        }

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);

        if (state == null) {
            return;
        }

        String previous = CallNumberStore.getLastState(context);

        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            // الرقم متاح هنا فقط إذا مُنح إذن سجل المكالمات؛ وإلا تلتقطه خدمة فحص المكالمات.
            @SuppressWarnings("deprecation")
            String number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);

            if (number != null && !number.trim().isEmpty()) {
                CallNumberStore.saveRinging(context, number);
            }
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            // رنين ثم رفع السماعة = تم الرد على مكالمة واردة.
            if (TelephonyManager.EXTRA_STATE_RINGING.equals(previous)) {
                CallNumberStore.promoteRinging(context);
            }
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            CallNumberStore.clearRinging(context);
        }

        CallNumberStore.setLastState(context, state);
    }
}

package iq.haji.cashier.util;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;

import androidx.appcompat.app.AlertDialog;

import iq.haji.cashier.R;
import iq.haji.cashier.data.AdminGuard;

/** يطلب رمز المدير قبل العمليات الحساسة. إذا لم يُعيَّن رمز تُنفَّذ العملية مباشرة. */
public final class PinDialog {
    private PinDialog() {}

    public static void require(Context ctx, final Runnable onOk) {
        final AdminGuard guard = new AdminGuard(ctx);
        if (!guard.isEnabled()) {
            onOk.run();
            return;
        }
        final EditText et = Ui.editText(ctx, "رمز المدير",
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, "");
        final AlertDialog d = new AlertDialog.Builder(ctx)
                .setTitle("صلاحية المدير")
                .setMessage("أدخل رمز المدير لتأكيد هذه العملية")
                .setView(Ui.wrap(ctx, et))
                .setPositiveButton(R.string.ok, null)
                .setNegativeButton(R.string.cancel, null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (guard.verify(et.getText().toString())) {
                d.dismiss();
                onOk.run();
            } else {
                et.setError("الرمز غير صحيح");
                et.setText("");
            }
        }));
        Ui.showRtl(d);
    }
}
